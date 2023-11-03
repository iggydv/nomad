import grpc from 'k6/net/grpc';
require('objects')
import { check } from 'k6';
import { sleep } from 'k6';

const client = new grpc.Client();
client.load(['../../../main/proto'], 'SuperPeerService.proto');
export default () => {
  client.connect('192.168.0.150:4640', {
     plaintext: true
  });

  const data = { hostname: '127.0.0.1:8080' };
  const response = client.invoke('grpc.superpeerservice.SuperPeerService/pingPeer', data);

  check(response, {
    'status is OK': (r) => r && r.status === grpc.StatusOK,
  });

  console.log(JSON.stringify(response.message));
  client.close();
  sleep(1);
};