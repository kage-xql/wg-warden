package com.example.wgwarden.peer;
import java.time.Instant;
import java.util.UUID;
public record PeerResponse(UUID id,String name,String publicKey,String allowedIp,boolean enabled,Instant createdAt,Long trafficLimitBytes,long trafficUsedBytes,Instant expiresAt,boolean configAvailable){
 static PeerResponse from(Peer p){return new PeerResponse(p.getId(),p.getName(),p.getPublicKey(),p.getAllowedIp(),p.isEnabled(),p.getCreatedAt(),p.getTrafficLimitBytes(),p.getTrafficUsedBytes(),p.getExpiresAt(),p.getEncryptedPrivateKey()!=null);}
}
