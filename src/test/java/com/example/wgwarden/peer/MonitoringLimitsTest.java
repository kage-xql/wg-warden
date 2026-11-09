package com.example.wgwarden.peer;

import com.example.wgwarden.monitoring.MonitorUpdate;
import com.example.wgwarden.monitoring.MonitoringEventHub;
import com.example.wgwarden.monitoring.MonitoringService;
import com.example.wgwarden.monitoring.PeerMetricRepository;
import com.example.wgwarden.monitoring.ServerMetricRepository;
import com.example.wgwarden.provisioning.WgInterface;
import com.example.wgwarden.provisioning.WgInterfaceRepository;
import com.example.wgwarden.server.Server;
import com.example.wgwarden.server.ServerRepository;
import com.example.wgwarden.server.ServerService;
import com.example.wgwarden.ssh.SshClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringLimitsTest {

    @Mock ServerRepository servers;
    @Mock WgInterfaceRepository interfaces;
    @Mock PeerRepository peers;
    @Mock ServerMetricRepository serverMetrics;
    @Mock PeerMetricRepository peerMetrics;
    @Mock SshClientService ssh;
    @Mock ServerService serverService;
    @Mock MonitoringEventHub events;
    @Mock PlatformTransactionManager txManager;
    @Mock PeerService peerService;

    MonitoringService monitoringService;

    UUID userId = UUID.randomUUID();
    UUID serverId = UUID.randomUUID();
    UUID interfaceId = UUID.randomUUID();
    UUID peerId = UUID.randomUUID();

    Server server;
    WgInterface wgInterface;
    Peer peer;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        monitoringService = new MonitoringService(
                servers, interfaces, peers, serverMetrics, peerMetrics,
                ssh, serverService, events, txManager, Duration.ofDays(30), peerService
        );

        server = new Server(userId, "srv", "1.2.3.4", 22, "root", new byte[0]);
        server.connectionResult("ssh-ed25519 AAA...", "Ubuntu");
        setField(server, "id", serverId);

        wgInterface = new WgInterface(serverId, "wg-warden0", 51820, "10.67.0.0/24", "pubKeyServer=", new byte[0]);
        setField(wgInterface, "id", interfaceId);

        peer = new Peer(interfaceId, "Phone", "pubKeyClient=", "10.67.0.2/32");
        setField(peer, "id", peerId);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void disablesPeerOnTrafficExceededWithoutDeleting() {
        peer.setEnabled(true);
        // Limit 100 MB
        peer.setTrafficLimitBytes(100L * 1024 * 1024);

        when(servers.findAll()).thenReturn(List.of(server));
        when(servers.findById(serverId)).thenReturn(java.util.Optional.of(server));
        when(interfaces.findAllByServerIdOrderByName(serverId)).thenReturn(List.of(wgInterface));
        when(peers.findAllByInterfaceIdOrderByCreatedAtDesc(interfaceId)).thenReturn(List.of(peer));

        // Output from remote server: 60MB rx + 50MB tx = 110MB > 100MB
        String sshOutput = "SYS\t1.0\t20.0\t30.0\t1000\t1000\n"
                + "WG\twg-warden0\tpubKeyClient=\t" + (Instant.now().getEpochSecond()) + "\t"
                + (60L * 1024 * 1024) + "\t" + (50L * 1024 * 1024) + "\n";

        when(peerMetrics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ssh.runSudoScript(eq("1.2.3.4"), eq(22), eq("root"), any(), any(), any())).thenReturn(sshOutput);

        monitoringService.collect();

        // Must call disableForLimit
        verify(peerService).disableForLimit(userId, peer);
        // Must never delete peer
        verify(peerService, never()).delete(any(), any());
        verify(peers, never()).delete(any());

        // Must publish update via events
        ArgumentCaptor<MonitorUpdate> captor = ArgumentCaptor.forClass(MonitorUpdate.class);
        verify(events).publish(eq(userId), captor.capture());
        MonitorUpdate update = captor.getValue();
        assertThat(update.peers()).hasSize(1);
        assertThat(update.peers().get(0).peerId()).isEqualTo(peerId);
    }

    @Test
    void disablesPeerWhenTimeExpiresEvenWithoutTraffic() {
        peer.setEnabled(true);
        peer.setExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));

        when(servers.findAll()).thenReturn(List.of(server));
        when(servers.findById(serverId)).thenReturn(java.util.Optional.of(server));
        when(interfaces.findAllByServerIdOrderByName(serverId)).thenReturn(List.of(wgInterface));
        when(peers.findAllByInterfaceIdOrderByCreatedAtDesc(interfaceId)).thenReturn(List.of(peer));

        // SYS only, no peer dump line
        String sshOutput = "SYS\t1.0\t20.0\t30.0\t1000\t1000\n";
        when(ssh.runSudoScript(eq("1.2.3.4"), eq(22), eq("root"), any(), any(), any())).thenReturn(sshOutput);

        monitoringService.collect();

        verify(peerService).disableForLimit(userId, peer);
        verify(peerService, never()).delete(any(), any());
    }
}
