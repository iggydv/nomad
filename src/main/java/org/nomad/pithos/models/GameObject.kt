package org.nomad.pithos.models

import java.io.Serializable
import javax.persistence.Entity
import javax.persistence.Id

@Entity
data class GameObject(@Id val id: String, val creationTime: Long, val lastModified: Long, val ttl: Long, val value: ByteArray) : Serializable {
}