package org.nomad.pithos.mappers

import org.mapstruct.Mapper
import org.nomad.pithos.mappers.CustomMappers
import org.nomad.grpc.models.GameObjectGrpc
import org.nomad.pithos.mappers.GameObjectMapper
import org.mapstruct.factory.Mappers
import org.nomad.pithos.models.GameObject

@Mapper(uses = [CustomMappers::class])
interface GameObjectMapper {
    fun mapToGrpc(gameObject: GameObject?): GameObjectGrpc?
    fun mapToInternal(gameObject: GameObjectGrpc?): GameObject?
    fun mapToInternal(gameObject: org.nomad.api.model.GameObject?): GameObject?
    fun mapToAPI(gameObject: GameObject?): org.nomad.api.model.GameObject?

    companion object {
        @JvmField
        val INSTANCE: GameObjectMapper = Mappers.getMapper(GameObjectMapper::class.java)
    }
}