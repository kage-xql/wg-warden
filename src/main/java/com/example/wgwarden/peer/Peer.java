package com.example.wgwarden.peer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="peers")
public class Peer {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="interface_id",nullable=false) private UUID interfaceId;
    @Column(nullable=false,length=100) private String name;
    @Column(name="public_key",nullable=false,length=64) private String publicKey;
    @Column(name="allowed_ip",nullable=false,length=64) private String allowedIp;
    @Column(nullable=false) private boolean enabled=true;
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="traffic_limit_bytes") private Long trafficLimitBytes;
    @Column(name="traffic_used_bytes",nullable=false) private long trafficUsedBytes = 0;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="encrypted_private_key") private byte[] encryptedPrivateKey;
    protected Peer(){}
    public Peer(UUID interfaceId,String name,String publicKey,String allowedIp){this.interfaceId=interfaceId;this.name=name;this.publicKey=publicKey;this.allowedIp=allowedIp;}
    @PrePersist void created(){if(createdAt==null)createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getInterfaceId(){return interfaceId;} public String getName(){return name;}
    public String getPublicKey(){return publicKey;} public String getAllowedIp(){return allowedIp;} public boolean isEnabled(){return enabled;}
    public Instant getExpiresAt(){return expiresAt;} public Instant getCreatedAt(){return createdAt;}
    public void setEnabled(boolean enabled){this.enabled=enabled;}
    public byte[] getEncryptedPrivateKey(){return encryptedPrivateKey;}
    public void setEncryptedPrivateKey(byte[] value){this.encryptedPrivateKey=value;}
    public void setName(String name){this.name=name;}
    public Long getTrafficLimitBytes(){return trafficLimitBytes;}
    public long getTrafficUsedBytes(){return trafficUsedBytes;}
    /** Добавляет дельту трафика к накопленному счётчику. Дельта всегда ≥ 0. */
    public void addTrafficUsedBytes(long delta){if(delta>0)this.trafficUsedBytes+=delta;}
    /** Сбрасывает накопленный счётчик (например, при изменении лимита пользователем). */
    public void resetTrafficUsedBytes(){this.trafficUsedBytes=0;}
    public void setExpiresAt(Instant value){this.expiresAt=value;}
    public void setTrafficLimitBytes(Long value){this.trafficLimitBytes=value;}
}
