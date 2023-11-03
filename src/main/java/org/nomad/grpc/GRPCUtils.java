package org.nomad.grpc;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class GRPCUtils {

    public static void checkGrpcCallStatus(StreamObserver responseObserver) {
        if (Context.current().isCancelled()) {
            responseObserver.onError(Status.CANCELLED.withDescription("Cancelled by client").asRuntimeException());
        }
    }
}
