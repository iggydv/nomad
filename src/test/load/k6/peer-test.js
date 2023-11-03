import grpc from 'k6/net/grpc';
// import { generateObject } from './object.js';
import faker from "https://raw.githubusercontent.com/Marak/faker.js/9c65e5dd4902dbdf12088c36c098a9d7958afe09/dist/faker.min.js";

import { check } from 'k6';
import { sleep } from 'k6';

const client = new grpc.Client();
client.load(['../../../main/proto'], 'PeerStorageService.proto');

export let options = {
  scenarios: {
    constant_request_rate: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s', // 10 iterations per second, i.e. 100 RPS
      duration: '30s',
      preAllocatedVUs: 100, // how large the initial pool of VUs would be
      maxVUs: 200, // if the preAllocatedVUs are not enough, we can initialize more
    },
  },
};

export const generateObject = () => ({
  id: faker.random.alphaNumeric(8),
  creationTime: faker.time.recent('unix'),
  ttl: 300,
  value: 'UnRGVkRZWEpRQkhubHVyUW9HTUpndHNaSTkxM3ZjTnVxcmlPUkVia2E2SzE2NWN1MUVxNnFIY3FnamZKSjlmaDl4elY3UHRweDAyR2VHc0labktyM2JOQ0tHZHdBQ2N0T3lHNUdObE9KT1NsSjk4UnVGQ1k1d09Yck92TnpoN3hJTlFWTHQ2SXgzRFAzdEY2dTh6Wld5cUI3VzZrbUZQNzlkR1R6akl3QmEwSUN1YlZKT2xUVmNBdW9mQXRqd3VWM0tVUHBUTXROa1NOa3o4TkcyVFJWMHdsYnlBaGdvbEg5eXFFOHNZTVpIVlh3cGM1a1l0eGlnRkVCZmU0RzZLdHMxcldRRUVWVzNta3IybVpMR2tWaXBXbU5nT0twRlZiaFMyNFZCdmZrMUZOQzFIY1U3QjFBbkpPOXQ3dmdic1FybTFGcnI2MXRObUk0MUVpcFhBRmdoYkhnZ1hGWmlQN3dJNzdJT1FMYjBjeXBFb1dXendhRnAxdVBIeTBxd2N3Ym5NTW14U1dFeXJOQ0hxVmdObGRCM3htQ1FISmN6QjB2WnRUaWRRaVlyTGVSTjJ5WFBvMURvTUgyUXptT1RNVU44TGNmQTY5eXNlY0RSQkJpV3ZDVDlLUnNzT3FOeDVqTkJ4Rk5SbHBWVkpLemFBcGl2ajNFN2xDdVpYRFAwT3h1S294SFdFZUJPWmc2ZEwyWEFvc05FSTlMT242ODFEY012VUNtbGR1TVhPRzZyRWEwck50aWtrcWFqRlpXUEcybGJHanZRR3U0aEh3SllnRDBzSlA3Q1NUdGRuR1p2eTFPVmw3TXNRcHZEellCdVhxMTk0YzZGcDIxWTN2THY0aHlaM1BJaVhSRG5Kd1IyV2h3ZGp1V0c0Y3hJczYwbjNEbU1XTzZJSkRXdk1wSTNtVXF5VEYwZVY3Tk95ZVBSOXpJUVBFaUpXekNVa1B1NWk4Z2Zid2JxSGlxTDBwUjZsaUFLdVZGWmxKTmtkdW5FRjZqdWVoSVVXMDNoOExPQUQzSFdDRmJBOTRJYmp4SURJRmlkcjR5VXlHc2MwQzR0VW9palVadEZ1Z2dlZGFidW80Y3VPQ2RtM2x6V0VJbXU4Q3lDcDlDd090cVpxZVZDdnA2M3lscG9sWkQ0eURXVkI2NEtkWlFkaFlBTzFrMm1hZDFJdkw5WHpsa0xNdTBPd1pmUHhVR09jaTM3M09GSVlwOXJIeVZzaER6eUZmQmZHMkRrOHB0SVV0YlIzR0lWT2IzWTEyejlRaGV4MGJ5QWJGT2t6d1RVNWhQVUFlNjFtRmF0UXFXU2NRRHJGOXVnemwwWFNBUEVyNmg3am5ucm9SM1RhWUUzTlRSSVRDN2NidA'
});

Array.prototype.sample = function(){
  return this[Math.floor(Math.random()*this.length)];
}

export default () => {


  const servers = ['192.168.0.150:5241', '192.168.0.150:6940', '192.168.0.166:6157', '192.168.0.182:5267']
  client.connect(servers.sample(), {
     plaintext: true
  });

  const gameObject = generateObject();
  const gameObject_data = {
     object: gameObject
  }

  const get_data = {
      id: faker.random.alphaNumeric(8)
  };

  const response_put = client.invoke('grpc.peerstorage.PeerStorageService/put', gameObject_data);
  const response_get = client.invoke('grpc.peerstorage.PeerStorageService/get', get_data);

  check(response_put, {
    'status is OK': (r) => r && r.status === grpc.StatusOK,
  });

  check(response_get, {
      'status is OK': (r) => r && r.status === grpc.StatusOK,
  });
};