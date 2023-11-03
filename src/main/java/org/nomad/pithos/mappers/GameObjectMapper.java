package org.nomad.pithos.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.pithos.models.GameObject;

@Mapper(uses = {CustomMappers.class})
public interface GameObjectMapper {
    GameObjectMapper INSTANCE = Mappers.getMapper(GameObjectMapper.class);

    GameObjectGrpc mapToGrpc(GameObject gameObject);

    GameObject mapToInternal(GameObjectGrpc gameObject);

    GameObject mapToInternal(org.nomad.api.model.GameObject gameObject);

    org.nomad.api.model.GameObject mapToAPI(GameObject gameObject);

}
