package com.example.wgwarden.peer;

import com.example.wgwarden.crypto.CryptoService;
import com.example.wgwarden.provisioning.WgInterface;
import com.example.wgwarden.provisioning.WgInterfaceRepository;
import com.example.wgwarden.server.Server;
import com.example.wgwarden.server.ServerRepository;
import com.example.wgwarden.server.ServerService;
import com.example.wgwarden.ssh.SshClientService;
import com.example.wgwarden.ssh.SshCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PeerServiceLimitsTest {

    @Mock PeerRepository peers;
    @Mock WgInterfaceRepository interfaces;
    @Mock ServerRepository servers;
    @Mock SshClientService ssh;
    @Mock ServerService serverService;
    @Mock CryptoService crypto;

    PeerService peerService;

    UUID userId = UUID.randomUUID();
    UUID serverId = UUID.randomUUID();
    UUID interfaceId = UUID.randomUUID();
    UUID peerId = UUID.randomUUID();

    Server server;
    WgInterface wgInterface;
    Peer peer;

    @BeforeEach
    void setUp() {
        peerService = new PeerService(peers, interfaces, servers, ssh, serverService, crypto);

        server = new Server(userId, "test-server", "192.168.1.1", 22, "root", new byte[0]);
        server.connectionResult("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI...", "Ubuntu");
        try {
            var idField = Server.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(server, serverId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        wgInterface = new WgInterface(serverId, "wg-warden0", 51820, "10.67.0.0/24", "serverPublicKey1234567890123456789012345678=", new byte[0]);
        try {
            var idField = WgInterface.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(wgInterface, interfaceId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        peer = new Peer(interfaceId, "Laptop", "clientPublicKey1234567890123456789012345678=", "10.67.0.2/32");
        try {
            var idField = Peer.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(peer, peerId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void disableForLimitDoesNotDeletePeerOnlyDisablesAndSaves() {
        peer.setEnabled(true);
        when(interfaces.findById(interfaceId)).thenReturn(Optional.of(wgInterface));
        when(servers.findByIdAndUserId(serverId, userId)).thenReturn(Optional.of(server));
        when(serverService.credentials(userId, serverId)).thenReturn(new SshCredentials("secret", null, null));

        peerService.disableForLimit(userId, peer);

        assertThat(peer.isEnabled()).isFalse();
        verify(peers).save(peer);
        verify(peers, never()).delete(any());
        verify(ssh).runSudoScript(eq("192.168.1.1"), eq(22), eq("root"), any(), any(), contains("wg syncconf"));
    }

    @Test
    void updatePeerLimitsCanExtendAndReenable() {
        // Expired in past
        peer.setEnabled(false);
        peer.setExpiresAt(Instant.now().minus(2, ChronoUnit.DAYS));
        peer.setTrafficLimitBytes(1024L * 1024L * 1024L);

        when(peers.findById(peerId)).thenReturn(Optional.of(peer));
        when(interfaces.findById(interfaceId)).thenReturn(Optional.of(wgInterface));
        when(interfaces.lockByIdAndServerId(interfaceId, serverId)).thenReturn(Optional.of(wgInterface));
        when(servers.findByIdAndUserId(serverId, userId)).thenReturn(Optional.of(server));
        when(serverService.credentials(userId, serverId)).thenReturn(new SshCredentials("secret", null, null));

        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        PeerUpdateRequest request = new PeerUpdateRequest("Laptop Pro", true, null, 10L * 1024 * 1024 * 1024, future, false, false);

        PeerResponse response = peerService.update(userId, peerId, request);

        assertThat(response.name()).isEqualTo("Laptop Pro");
        assertThat(response.trafficLimitBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(response.expiresAt()).isEqualTo(future);
        assertThat(peer.isEnabled()).isTrue();
        verify(peers, atLeastOnce()).save(peer);
        verify(ssh).runSudoScript(eq("192.168.1.1"), eq(22), eq("root"), any(), any(), contains("wg syncconf"));
    }

    @Test
    void cannotEnablePeerIfLimitIsExpired() {
        peer.setEnabled(false);
        peer.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(peers.findById(peerId)).thenReturn(Optional.of(peer));
        when(interfaces.findById(interfaceId)).thenReturn(Optional.of(wgInterface));
        when(servers.findByIdAndUserId(serverId, userId)).thenReturn(Optional.of(server));

        assertThatThrownBy(() -> peerService.setEnabled(userId, peerId, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage limit has expired");

        verify(ssh, never()).runSudoScript(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void clearLimitsSetsThemToNull() {
        peer.setExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));
        peer.setTrafficLimitBytes(5L * 1024 * 1024 * 1024);

        when(peers.findById(peerId)).thenReturn(Optional.of(peer));
        when(interfaces.findById(interfaceId)).thenReturn(Optional.of(wgInterface));
        when(servers.findByIdAndUserId(serverId, userId)).thenReturn(Optional.of(server));

        PeerUpdateRequest request = new PeerUpdateRequest(null, null, null, null, null, true, true);
        PeerResponse response = peerService.update(userId, peerId, request);

        assertThat(response.trafficLimitBytes()).isNull();
        assertThat(response.expiresAt()).isNull();
        verify(peers).save(peer);
    }
}
