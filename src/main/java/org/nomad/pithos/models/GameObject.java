package org.nomad.pithos.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.io.Serializable;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GameObject implements Serializable {
    @Id
    private String id;
    private long creationTime;
    private long lastModified;
    private long ttl;
    private byte[] value;
}
