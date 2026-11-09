package com.example.wgwarden.peer;

import com.example.wgwarden.provisioning.WgInterface;
import com.example.wgwarden.provisioning.WgInterfaceRepository;
import com.example.wgwarden.provisioning.WireGuardValidation;
import com.example.wgwarden.server.Server;
import com.example.wgwarden.server.ServerNotFoundException;
import com.example.wgwarden.server.ServerRepository;
import com.example.wgwarden.server.ServerService;
import com.example.wgwarden.ssh.SshClientService;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import com.example.wgwarden.crypto.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class PeerService {
    private static final String APP_INTERFACE="wg-warden0";
    private final PeerRepository peers; private final WgInterfaceRepository interfaces; private final ServerRepository servers;
    private final SshClientService ssh; private final ServerService serverService; private final CryptoService crypto; private final SecureRandom random=new SecureRandom();
    public PeerService(PeerRepository peers,WgInterfaceRepository interfaces,ServerRepository servers,SshClientService ssh,ServerService serverService,CryptoService crypto){this.peers=peers;this.interfaces=interfaces;this.servers=servers;this.ssh=ssh;this.serverService=serverService;this.crypto=crypto;}

    @Transactional(readOnly=true)
    public List<PeerResponse> list(UUID userId,UUID serverId){
        Server server=servers.findByIdAndUserId(serverId,userId).orElseThrow(ServerNotFoundException::new);
        return interfaces.findAllByServerIdOrderByName(server.getId()).stream().flatMap(wg->peers.findAllByInterfaceIdOrderByCreatedAtDesc(wg.getId()).stream()).map(PeerResponse::from).toList();
    }

    @Transactional
    public PeerCreationResponse create(UUID userId,UUID serverId,PeerRequest request){
        Server server=servers.findByIdAndUserId(serverId,userId).orElseThrow(ServerNotFoundException::new);
        WgInterface wg=interfaces.findByServerIdAndName(serverId,APP_INTERFACE).orElseThrow(()->new IllegalStateException("provision WireGuard on this server first"));
        WgInterface locked=interfaces.lockByIdAndServerId(wg.getId(),serverId).orElseThrow(()->new IllegalStateException("WireGuard interface not found"));
        ensurePinned(server);
        String ip=nextAddress(locked);
        KeyPair pair=generateKeyPair();
        String name=request.name().trim();
        ssh.runSudoScript(server.getHost(),server.getSshPort(),server.getSshUser(),serverService.credentials(userId,serverId),server.getHostKeyFingerprint(),addPeerScript(locked.getName(),pair.publicKey(),ip));
        // Сгенерированный приватный ключ отдаётся один раз в ответе и не сохраняется.
        Peer peer=new Peer(locked.getId(),name,pair.publicKey(),ip+"/32"); peer.setTrafficLimitBytes(request.trafficLimitBytes()!=null&&request.trafficLimitBytes()>0?request.trafficLimitBytes():null); peer.setExpiresAt(request.expiresAt()); peer=peers.save(peer);
        String config=clientConfig(server,locked,ip,pair.privateKey());
        return new PeerCreationResponse(PeerResponse.from(peer),config,qr(config));
    }

    @Transactional
    public PeerResponse update(UUID userId,UUID peerId,PeerUpdateRequest request){
        Peer peer=ownedPeer(userId,peerId);
        if(request.name()!=null){String name=request.name().trim();if(name.isBlank())throw new IllegalArgumentException("peer name cannot be blank");peer.setName(name);}
        if(request.privateKey()!=null&&!request.privateKey().isBlank())peer.setEncryptedPrivateKey(crypto.encrypt(request.privateKey().trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(Boolean.TRUE.equals(request.clearTrafficLimit())){peer.setTrafficLimitBytes(null);}
        else if(request.trafficLimitBytes()!=null){peer.setTrafficLimitBytes(request.trafficLimitBytes()>0?request.trafficLimitBytes():null);}
        if(Boolean.TRUE.equals(request.clearExpiresAt())){peer.setExpiresAt(null);}
        else if(request.expiresAt()!=null){peer.setExpiresAt(request.expiresAt());}
        peers.save(peer);
        if(request.enabled()!=null&&peer.isEnabled()!=request.enabled())setEnabled(userId,peerId,request.enabled());
        return PeerResponse.from(peer);
    }

    @Transactional(readOnly=true)
    public PeerCreationResponse config(UUID userId,UUID peerId){
        Peer peer=ownedPeer(userId,peerId);if(peer.getEncryptedPrivateKey()==null)throw new IllegalStateException("client private key is not available; edit the imported peer and provide its private key");
        WgInterface wg=interfaces.findById(peer.getInterfaceId()).orElseThrow(PeerNotFoundException::new);Server server=servers.findByIdAndUserId(wg.getServerId(),userId).orElseThrow(ServerNotFoundException::new);
        String text=clientConfig(server,wg,address(peer.getAllowedIp()),new String(crypto.decrypt(peer.getEncryptedPrivateKey()),java.nio.charset.StandardCharsets.UTF_8));
        return new PeerCreationResponse(PeerResponse.from(peer),text,qr(text));
    }

    @Transactional
    public PeerResponse setEnabled(UUID userId,UUID peerId,boolean enabled){
        Peer peer=ownedPeer(userId,peerId);
        if(enabled&&peer.getExpiresAt()!=null&&!peer.getExpiresAt().isAfter(Instant.now())){
            throw new IllegalArgumentException("Cannot enable peer: usage limit has expired. Update peer limits first.");
        }
        interfaces.lockByIdAndServerId(peer.getInterfaceId(),serverIdFor(userId,peer)).orElseThrow(PeerNotFoundException::new);
        if(peer.isEnabled()!=enabled){
            Server server=servers.findByIdAndUserId(serverIdFor(userId,peer),userId).orElseThrow(ServerNotFoundException::new);
            ensurePinned(server);
            WgInterface wg=interfaces.findById(peer.getInterfaceId()).orElseThrow(PeerNotFoundException::new);
            ssh.runSudoScript(server.getHost(),server.getSshPort(),server.getSshUser(),serverService.credentials(userId,server.getId()),server.getHostKeyFingerprint(),enabled?addPeerScript(wg.getName(),peer.getPublicKey(),address(peer.getAllowedIp())):removePeerScript(wg.getName(),peer.getPublicKey()));
            peer.setEnabled(enabled);
            peers.save(peer);
        }
        return PeerResponse.from(peer);
    }

    @Transactional
    public void delete(UUID userId,UUID peerId){
        Peer peer=ownedPeer(userId,peerId); UUID serverId=serverIdFor(userId,peer);
        interfaces.lockByIdAndServerId(peer.getInterfaceId(),serverId).orElseThrow(PeerNotFoundException::new);
        Server server=servers.findByIdAndUserId(serverId,userId).orElseThrow(ServerNotFoundException::new);
        ensurePinned(server);
        WgInterface wg=interfaces.findById(peer.getInterfaceId()).orElseThrow(PeerNotFoundException::new);
        ssh.runSudoScript(server.getHost(),server.getSshPort(),server.getSshUser(),serverService.credentials(userId,serverId),server.getHostKeyFingerprint(),removePeerScript(wg.getName(),peer.getPublicKey()));
        peers.delete(peer);
    }

    @Transactional
    public void disableForLimit(UUID userId,Peer peer){
        if(!peer.isEnabled())return; UUID serverId=serverIdFor(userId,peer); Server server=servers.findByIdAndUserId(serverId,userId).orElseThrow(ServerNotFoundException::new); ensurePinned(server); WgInterface wg=interfaces.findById(peer.getInterfaceId()).orElseThrow(PeerNotFoundException::new);
        ssh.runSudoScript(server.getHost(),server.getSshPort(),server.getSshUser(),serverService.credentials(userId,serverId),server.getHostKeyFingerprint(),removePeerScript(wg.getName(),peer.getPublicKey())); peer.setEnabled(false);
        peers.save(peer);
    }

    private Peer ownedPeer(UUID userId,UUID peerId){
        Peer peer=peers.findById(peerId).orElseThrow(PeerNotFoundException::new);
        UUID serverId=interfaces.findById(peer.getInterfaceId()).map(WgInterface::getServerId).orElseThrow(PeerNotFoundException::new);
        if(servers.findByIdAndUserId(serverId,userId).isEmpty())throw new PeerNotFoundException();
        return peer;
    }
    private UUID serverIdFor(UUID userId,Peer peer){
        UUID serverId=interfaces.findById(peer.getInterfaceId()).map(WgInterface::getServerId).orElseThrow(PeerNotFoundException::new);
        if(servers.findByIdAndUserId(serverId,userId).isEmpty())throw new PeerNotFoundException();
        return serverId;
    }
    private void ensurePinned(Server server){if(server.getHostKeyFingerprint()==null)throw new IllegalStateException("server SSH host key is not pinned");}

    private String nextAddress(WgInterface wg){
        try{
            String[] parts=wg.getSubnet().split("/");int prefix=Integer.parseInt(parts[1]);
            byte[] bytes=InetAddress.getByName(parts[0]).getAddress();
            if(bytes.length!=4||prefix<16||prefix>30)throw new IllegalStateException("only IPv4 subnets from /16 through /30 are supported");
            long address=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes).getInt());
            long mask=(0xffffffffL << (32-prefix))&0xffffffffL;long network=address&mask;long broadcast=network|(~mask&0xffffffffL);
            Set<Long> used=new HashSet<>();
            for(Peer peer:peers.findAllByInterfaceIdOrderByCreatedAtDesc(wg.getId()))used.add(ipv4Long(address(peer.getAllowedIp())));
            long gateway=network+1;
            for(long candidate=network+2;candidate<broadcast;candidate++)if(candidate!=gateway&&!used.contains(candidate))return ipv4String(candidate);
            throw new IllegalStateException("WireGuard subnet has no available client addresses");
        }catch(IllegalArgumentException e){throw new IllegalStateException("invalid WireGuard subnet",e);}catch(java.net.UnknownHostException e){throw new IllegalStateException("invalid WireGuard subnet",e);}
    }
    private long ipv4Long(String value){try{return Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(InetAddress.getByName(address(value)).getAddress()).getInt());}catch(Exception e){throw new IllegalStateException("stored peer IP is invalid",e);}}
    private String address(String value){int slash=value.indexOf('/');return slash<0?value:value.substring(0,slash);}
    private String ipv4String(long ip){return ((ip>>>24)&255)+"."+((ip>>>16)&255)+"."+((ip>>>8)&255)+"."+(ip&255);}
    private record KeyPair(String privateKey,String publicKey){}
    private KeyPair generateKeyPair(){byte[] privateBytes=new X25519PrivateKeyParameters(random).getEncoded();byte[] publicBytes=new X25519PrivateKeyParameters(privateBytes,0).generatePublicKey().getEncoded();return new KeyPair(Base64.getEncoder().encodeToString(privateBytes),Base64.getEncoder().encodeToString(publicBytes));}
    private void requireSafeScriptValues(String interfaceName,String publicKey,String ip){
        if(interfaceName==null||!interfaceName.matches("[A-Za-z0-9_.-]{1,15}"))
            throw new IllegalStateException("stored WireGuard interface name is invalid");
        if(!WireGuardValidation.publicKey(publicKey))
            throw new IllegalStateException("stored peer public key is invalid");
        if(ip!=null&&!WireGuardValidation.ipv4(ip))
            throw new IllegalStateException("stored peer address is invalid");
    }
    private String clientConfig(Server server,WgInterface wg,String ip,String privateKey){
        String endpoint=server.getHost().contains(":")&&!server.getHost().startsWith("[")?"["+server.getHost()+"]":server.getHost();
        return "[Interface]\nPrivateKey = "+privateKey+"\nAddress = "+ip+"/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = "+wg.getPublicKey()+"\nEndpoint = "+endpoint+":"+wg.getListenPort()+"\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n";
    }
    private String qr(String text){
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            MatrixToImageWriter.writeToStream(new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,512,512),"PNG",out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }catch(Exception e){throw new IllegalStateException("could not create peer QR code",e);}
    }
    private String addPeerScript(String interfaceName,String publicKey,String ip){
        requireSafeScriptValues(interfaceName,publicKey,ip);
        return "set -euo pipefail\nexec 9>/run/lock/wg-warden-"+interfaceName+".lock\nflock -x 9\nconf=/etc/wireguard/"+interfaceName+".conf\n"+
                "grep -qF 'PublicKey = "+publicKey+"' \"$conf\" || cat >> \"$conf\" <<'WG_PEER'\n[Peer]\nPublicKey = "+publicKey+"\nAllowedIPs = "+ip+"/32\nWG_PEER\n"+
                "wg syncconf "+interfaceName+" <(wg-quick strip "+interfaceName+")\n";
    }
    private String removePeerScript(String interfaceName,String publicKey){
        requireSafeScriptValues(interfaceName,publicKey,null);
        return "set -euo pipefail\nexec 9>/run/lock/wg-warden-"+interfaceName+".lock\nflock -x 9\nconf=/etc/wireguard/"+interfaceName+".conf\ntmp=$(mktemp)\n"+
                "awk -v key='"+publicKey+"' 'function flush(){if(!drop)for(i=1;i<=n;i++)print lines[i];delete lines;n=0;drop=0} " +
                "/^\\[Peer\\][[:space:]]*$/ {flush();peer=1;lines[++n]=$0;next} peer {lines[++n]=$0;if($1==\"PublicKey\"&&$2==\"=\"&&$3==key)drop=1;next} {print} END{flush()}' \"$conf\" > \"$tmp\"\n"+
                "install -m 600 \"$tmp\" \"$conf\"; rm -f \"$tmp\"\nwg syncconf "+interfaceName+" <(wg-quick strip "+interfaceName+")\n";
    }
}
