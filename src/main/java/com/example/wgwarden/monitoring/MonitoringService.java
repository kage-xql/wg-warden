package com.example.wgwarden.monitoring;

import com.example.wgwarden.peer.Peer;
import com.example.wgwarden.peer.PeerRepository;
import com.example.wgwarden.peer.PeerService;
import com.example.wgwarden.provisioning.WgInterface;
import com.example.wgwarden.provisioning.WgInterfaceRepository;
import com.example.wgwarden.server.Server;
import com.example.wgwarden.server.ServerRepository;
import com.example.wgwarden.server.ServerService;
import com.example.wgwarden.ssh.SshClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Service
public class MonitoringService {
    private static final Logger log=LoggerFactory.getLogger(MonitoringService.class);
    private static final String APP_INTERFACE="wg-warden0";
    private static final String COLLECT_SCRIPT="""
        set -euo pipefail
        cores=$(getconf _NPROCESSORS_ONLN)
        cpu=$(awk -v n="$cores" '{value=($1/n)*100; if(value>100)value=100; printf "%.2f",value}' /proc/loadavg)
        ram=$(awk '/MemTotal:/ {total=$2} /MemAvailable:/ {available=$2} END {printf "%.2f", (total-available)*100/total}' /proc/meminfo)
        disk=$(df -P / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
        read -r net_rx net_tx < <(awk -F: 'NR>2 {gsub(/ /,"",$1); if($1!="lo" && $1 !~ /^wg/){rx+=$2; tx+=$10}} END {printf "%s %s\\n",rx+0,tx+0}' /proc/net/dev)
        printf 'SYS\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$cpu" "$ram" "$disk" "$net_rx" "$net_tx"
        for iface in $(wg show interfaces 2>/dev/null); do
          (wg show "$iface" dump 2>/dev/null || true) | awk -v iface="$iface" 'NF>=8 {printf "WG\\t%s\\t%s\\t%s\\t%s\\t%s\\n",iface,$1,$5,$6,$7}'
        done
        """;

    private final ServerRepository servers;
    private final WgInterfaceRepository interfaces;
    private final PeerRepository peers;
    private final ServerMetricRepository serverMetrics;
    private final PeerMetricRepository peerMetrics;
    private final SshClientService ssh;
    private final ServerService serverService;
    private final MonitoringEventHub events;
    private final TransactionTemplate transactions;
    private final java.time.Duration retention;
    private final PeerService peerService;

    public MonitoringService(ServerRepository servers,WgInterfaceRepository interfaces,PeerRepository peers,
            ServerMetricRepository serverMetrics,PeerMetricRepository peerMetrics,SshClientService ssh,
            ServerService serverService,MonitoringEventHub events,org.springframework.transaction.PlatformTransactionManager txManager,
            @Value("${app.monitoring.retention:30d}") java.time.Duration retention,PeerService peerService){
        this.servers=servers;this.interfaces=interfaces;this.peers=peers;this.serverMetrics=serverMetrics;this.peerMetrics=peerMetrics;
        this.ssh=ssh;this.serverService=serverService;this.events=events;this.transactions=new TransactionTemplate(txManager);this.retention=retention;this.peerService=peerService;
    }

    @Scheduled(cron="0 20 3 * * *")
    public void purgeOldMetrics(){
        Instant cutoff=Instant.now().minus(retention);
        transactions.execute(status->{serverMetrics.deleteByTimestampBefore(cutoff);peerMetrics.deleteByTimestampBefore(cutoff);return null;});
    }

    @Scheduled(fixedDelayString="${app.monitoring.interval:60s}",initialDelayString="${app.monitoring.initial-delay:5s}")
    public void collect(){
        for(Server server:servers.findAll()){
            UUID id=server.getId();
            try{transactions.execute(status->{collectOne(id);return null;});}
            catch(RuntimeException e){
                Throwable root=e;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
                String reason=root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();
                log.warn("Monitoring failed for server {}: {}",id,reason,e);
                try { transactions.execute(status->{servers.findById(id).ifPresent(current->{
                    String message=("Monitoring failed: "+reason);
                    if(message.length()>250)message=message.substring(0,247)+"...";
                    current.markError(message);
                    events.publish(current.getUserId(),new MonitorUpdate(id,current.getStatus().name(),message,null,List.of()));
                });return null;}); } catch (RuntimeException markError) { log.warn("Could not persist monitoring error for {}", id, markError); }
            }
        }
    }

    private void collectOne(UUID serverId){
        Server server=servers.findById(serverId).orElse(null);
        if(server==null||server.getHostKeyFingerprint()==null)return;
        String output=ssh.runSudoScript(server.getHost(),server.getSshPort(),server.getSshUser(),
                serverService.credentials(server.getUserId(),serverId),server.getHostKeyFingerprint(),COLLECT_SCRIPT);
        Instant now=Instant.now();ServerMetric serverMetric=null;Map<String,Map<String,String[]>> peerDump=new HashMap<>();
        for(String line:output.lines().toList()){
            String[] fields=line.split("\\t");
            if(fields.length==6&&fields[0].equals("SYS")){
                serverMetric=new ServerMetric(serverId,now,metric(fields[1]),metric(fields[2]),metric(fields[3]),counter(fields[4]),counter(fields[5]));
            }else if(fields.length==6&&fields[0].equals("WG")){
                peerDump.computeIfAbsent(fields[1],key->new HashMap<>()).put(fields[2],new String[]{fields[3],fields[4],fields[5]});
            }
        }
        if(serverMetric==null)throw new IllegalStateException("remote metrics response is incomplete");
        serverMetrics.save(serverMetric);List<MonitorUpdate.PeerUpdate> peerUpdates=new ArrayList<>();
        for(WgInterface wg:interfaces.findAllByServerIdOrderByName(serverId)) for(Peer peer:peers.findAllByInterfaceIdOrderByCreatedAtDesc(wg.getId())){
            boolean expired=peer.getExpiresAt()!=null&&!peer.getExpiresAt().isAfter(now);
            String[] values=peerDump.getOrDefault(wg.getName(),Map.of()).get(peer.getPublicKey());
            if(values!=null){
                long epoch=counter(values[0]);Instant handshake=epoch==0?null:Instant.ofEpochSecond(epoch);
                PeerMetric metric=peerMetrics.save(new PeerMetric(peer.getId(),now,counter(values[1]),counter(values[2]),handshake));
                boolean overTraffic=peer.getTrafficLimitBytes()!=null&&counter(values[1])+counter(values[2])>=peer.getTrafficLimitBytes();
                if(expired||overTraffic)try{peerService.disableForLimit(server.getUserId(),peer);}catch(RuntimeException e){log.warn("Could not disable limited peer {}",peer.getId(),e);}
                peerUpdates.add(new MonitorUpdate.PeerUpdate(peer.getId(),peer.isEnabled(),PeerMetricResponse.from(metric)));
            } else if(expired&&peer.isEnabled()){
                try{peerService.disableForLimit(server.getUserId(),peer);}catch(RuntimeException e){log.warn("Could not disable expired peer {}",peer.getId(),e);}
                peerUpdates.add(new MonitorUpdate.PeerUpdate(peer.getId(),peer.isEnabled(),null));
            }
        }
        server.markOnline();events.publish(server.getUserId(),new MonitorUpdate(serverId,server.getStatus().name(),null,ServerMetricResponse.from(serverMetric),peerUpdates));
    }
    private double metric(String value){double number=Double.parseDouble(value);if(!Double.isFinite(number)||number<0||number>100)throw new IllegalArgumentException("Invalid percentage metric");return number;}
    private long counter(String value){long number=Long.parseLong(value);if(number<0)throw new IllegalArgumentException("Invalid counter metric");return number;}
}
