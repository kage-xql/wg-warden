package com.example.wgwarden.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class SshClientService {
    private static final Logger log = LoggerFactory.getLogger(SshClientService.class);
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    public record ProbeResult(String fingerprint,String operatingSystem) {}
    public ProbeResult probe(String host,int port,String username,SshCredentials credentials,String pinnedFingerprint) {
        final String[] observed={null};
        try(SshClient client=SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session,address,key)->{
                final String fp;
                try {
                    fp=fingerprint(key);
                } catch (Exception e) {
                    return false;
                }
                observed[0]=fp;
                return pinnedFingerprint==null || pinnedFingerprint.equals(fp);
            });
            client.start();
            try(ClientSession session=client.connect(username,host,port).verify(10,TimeUnit.SECONDS).getSession()) {
                if(credentials.privateKey()!=null && !credentials.privateKey().isBlank()) {
                    byte[] key=credentials.privateKey().getBytes(StandardCharsets.UTF_8);
                    var identities=SecurityUtils.loadKeyPairIdentities(null, () -> "key", new ByteArrayInputStream(key),
                            credentials.passphrase()==null ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(credentials.passphrase()));
                    if (identities == null || !identities.iterator().hasNext()) {
                        throw new IllegalArgumentException("No private key could be loaded; paste the complete private key, not the .pub file");
                    }
                    identities.forEach(session::addPublicKeyIdentity);
                } else session.addPasswordIdentity(credentials.password());
                session.auth().verify(15,TimeUnit.SECONDS);
                String release=execute(session,"cat /etc/os-release");
                String id=release.lines().filter(line->line.startsWith("ID=")).map(line->line.substring(3).replace("\"", "").trim()).findFirst().orElse("");
                if(!id.equals("ubuntu")&&!id.equals("debian")) throw new IllegalStateException("unsupported operating system");
                return new ProbeResult(observed[0],id.substring(0,1).toUpperCase()+id.substring(1));
            }
        } catch (Exception e) {
            String message=e instanceof IllegalStateException&&"unsupported operating system".equals(e.getMessage())
                    ? "Unsupported operating system; use Ubuntu or Debian"
                    : "SSH connection failed; check address, host key and credentials";
            log.warn("SSH probe failed for {}@{}:{}: {}", username, host, port, safeReason(e), e);
            throw new SshConnectionException(message,e);
        }
    }
    private String safeReason(Throwable error) {
        Throwable cause=error;
        while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
        String reason=cause.getMessage();
        return reason==null||reason.isBlank()?cause.getClass().getSimpleName():reason;
    }
    /** Последняя строка stderr без секретов: помогает понять, на каком шаге упал скрипт. */
    private static String lastSafeLine(String text) {
        String[] lines=text.split("\\R");
        for(int i=lines.length-1;i>=0;i--) {
            String line=lines[i].strip();
            if(line.isEmpty()||line.contains("PRIVATE")||line.contains("PrivateKey")) continue;
            return line.length()>300?line.substring(0,300)+"...":line;
        }
        return "";
    }
    public String runSudoScript(String host,int port,String username,SshCredentials credentials,String pinnedFingerprint,String script) {
        try(SshClient client=SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session,address,key)->{try{return pinnedFingerprint.equals(fingerprint(key));}catch(Exception e){return false;}});
            client.start();
            try(ClientSession session=client.connect(username,host,port).verify(10,TimeUnit.SECONDS).getSession()) {
                authenticate(session,credentials);
                try(var channel=session.createExecChannel("sudo -n bash -s");
                    var out=new LimitedOutputStream(MAX_OUTPUT_BYTES);
                    var err=new LimitedOutputStream(MAX_ERROR_BYTES)) {
                    channel.setIn(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
                    channel.setOut(out);channel.setErr(err);channel.open().verify(10,TimeUnit.SECONDS);
                    var events=channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),TimeUnit.MINUTES.toMillis(3));
                    if(!events.contains(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED))
                        throw new IOException("Remote SSH command timed out after 3 minutes");
                    if(channel.getExitStatus()==null) throw new IOException("Remote SSH command closed without an exit status");
                    if(channel.getExitStatus()!=0) {
                        // stderr содержит только диагностику скрипта; stdout с ключами в ответ не попадает.
                        String detail=lastSafeLine(err.asString());
                        throw new IOException("Remote SSH command exited with status "+channel.getExitStatus()
                                +(detail.isBlank()?"":": "+detail));
                    }
                    return out.asString().trim();
                }
            }
        } catch(Exception e) {
            // В выводе удалённой команды могут быть ключи и другие секреты,
            // поэтому в лог и клиенту отдаём только тип/краткую причину.
            log.error("Remote SSH command failed for {}@{}:{} ({})", username, host, port,
                    e.getClass().getSimpleName());
            throw new SshConnectionException("remote SSH command failed: "+safeReason(e),e);
        }
    }
    private void authenticate(ClientSession session,SshCredentials credentials) throws Exception {
        if(credentials.privateKey()!=null&&!credentials.privateKey().isBlank()) {
            var identities=SecurityUtils.loadKeyPairIdentities(null,()->"key",new ByteArrayInputStream(credentials.privateKey().getBytes(StandardCharsets.UTF_8)),
                    credentials.passphrase()==null?FilePasswordProvider.EMPTY:FilePasswordProvider.of(credentials.passphrase()));
            if (identities == null || !identities.iterator().hasNext()) throw new IllegalArgumentException("No private key could be loaded");
            identities.forEach(session::addPublicKeyIdentity);
        } else session.addPasswordIdentity(credentials.password());
        session.auth().verify(15,TimeUnit.SECONDS);
    }
    private String execute(ClientSession session,String command) throws IOException {
        try(var channel=session.createExecChannel(command); var out=new ByteArrayOutputStream()) {
            channel.setOut(out); channel.open().verify(10,TimeUnit.SECONDS); channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),TimeUnit.SECONDS.toMillis(15));
            if(channel.getExitStatus()==null||channel.getExitStatus()!=0) throw new IOException("remote command failed");
            return out.toString(StandardCharsets.UTF_8);
        }
    }
    private String fingerprint(PublicKey key) throws Exception { return "SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())); }

    /** Ограничивает объём вывода, чтобы скомпрометированный сервер не исчерпал память панели. */
    private static final class LimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int limit;

        private LimitedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(data, offset, length);
        }

        private void ensureCapacity(int extra) throws IOException {
            if ((long) delegate.size() + extra > limit) {
                throw new IOException("Remote SSH command output exceeded " + limit + " bytes");
            }
        }

        private String asString() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }
}
