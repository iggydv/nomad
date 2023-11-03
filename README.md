# Getting Started

[Nomad Demo Video](https://drive.google.com/file/d/10QggzLRd0byQ64ATOqQ9KJWa1NSdxRSV/view?usp=sharing)

## Prerequisites
- [Docker](https://docs.docker.com/get-docker/)
- [Docker Compose](https://docs.docker.com/compose/install/)
- [Maven3](https://maven.apache.org/install.html)
- [java 1.8 (jre/jdk)](https://www.java.com/en/download/help/download_options.xml)
- A local copy of this repository


## Run the application locally
Navigate to the repository
```bash
cd ~/path/to/dir/nomad
```

If maven fails to resolve either DHT dependencies (tomp2p or open-chord), you can manually install them using:
_Note: open-chord is not fully functional and should not be used in any real-world application_

```bash
mvn install:install-file -Dfile=lib/open-chord-1.0.6.jar -DgroupId=com.chord -DartifactId=open-chord -Dversion=1.0.6
mvn install:install-file -Dfile=lib/tomp2p-all-5.0-Beta8.1.jar -DgroupId=net.tomp2p -DartifactId=tomp2p-all -Dversion=5.0-Beta8.1
```

Compile the application locally
```bash
mvn clean install
# Skip testing phase
mvn clean install -DskipTests
```

### Create a Directory server

Start the required docker containers - `Apache ZooKeeper`
```bash
docker-compose -f stack.yml up 
```

### Create a Nomad Network

Update configuration 
```yaml
# NOTE:
# Configuration not mentioned here should only be changed if you know what you're doing

spring:
  schedule:
    healthCheck: 20000
    dhtBootstrap: 500000
    dbCleanup: 60000
    ledgerCleanup: 60000
    # scheduled repair causes a significant amount of additional load depending on the storage method.
    # this additional load will decrease performance of the network.
    # Leave repair is built into the logic
    repair: 6000000
    updatePosition: 10000

node:
  # Used for testing only
  malicious: false
  group:
    migration: true # Used to enable group peer migration
    voronoiGrouping: true # Used to enable Voronoi grouping mechanism
  maxPeers: 25 # Max group size
  storage:
    replicationFactor: 6 # Group storage replication factor
    mode: "h2" # default storage implementation
    storageMode: "fast" # Storage mode [fast, safe]
    retrievalMode: "parallel" # Retrieval mode [fast, parallel, safe]
  directoryServer:
    path: "/GroupStorage"
    hostname: "<zookeeper-ip>" # Use 127.0.0.1 for local ZooKeeper instances
  networkHostnames:
    peerServer: ""
    peerStorageServer: ""
    overlayHostname: ""
    superPeerServer: ""
    groupStorageServer: ""
  world:
    # VE Map limits
    height: 10.0
    width: 10.0
```

#### Create a Super-peer

The first peer in the network, will by default become a Super-peer. A super-peer by design can't process storage requests,
we will therefore require at least on more peer.

```bash
$ java -XX:+Use<GC-of-your-choosing> -jar target/Nomad-1.0-SNAPSHOT.jar -m <mode> [h2, rocksdb] -t <peer-type> [super-peer, peer] (all parameters are optional)

example:
--------
$ java -XX:+UseG1GC -jar target/Nomad-1.0-SNAPSHOT.jar -m h2 -t super-peer
$ java -XX:+UseG1GC -jar target/Nomad-1.0-SNAPSHOT.jar
```

The following log statement indicates that the super-peer has been successfully created
```bash
[main] INFO  org.nomad.pithos.MainController - Super Peer status: SERVING
```

#### Create Storage Peers

In a separate terminal, execute the `jar` again, this will spin up a new instance and assign it as a storage peer.

```bash
$ java -XX:+UseG1GC -jar target/Nomad-1.0-SNAPSHOT.jar -t peer
.
.
.
[main] INFO  org.nomad.pithos.MainController - Peer status: SERVING
[main] INFO  org.nomad.pithos.MainController - http://<ip>:<port>/nomad/swagger-ui/index.html
```

That's it, you now have a functional group, with a `super-peer` and `peer`.

### Testing the network

There are two ways to test the network,

1. Manually accessing the rest endpoint via a browser or http client
    * The access point URL can be found directly in the logs
    * `http://<ip>:<port>/nomad/swagger-ui/index.html`
2. Using predefined load test via [artillery](https://artillery.io/docs/guides/overview/welcome.html)
    * [Installing artillery](https://artillery.io/docs/guides/getting-started/installing-artillery.html)
    * Update `src/test/load/simple-test.yaml` -> `target: http://<ip>:<port>` (same IP:port used to access swagger page for manual testing)
    * Run the load-test in a separate terminal:
        ```bash
        artillery run src/test/load/put-test.yaml
        ```
    * example output:  
        ```bash
        All virtual users finished
        Summary report @ 10:59:48(+0100) 2021-03-12
        Scenarios launched:  300
        Scenarios completed: 300
        Requests completed:  600
        Mean response/sec: 19.82
        Response time (msec):
        min: 0.9
        max: 490.8
        median: 2.1
        p95: 17.4
        p99: 34.6
        Scenario counts:
        0: 300 (100%)
        Codes:
        200: xxx
        201: xxx
        ```

## General Documentation

[Nomad Documentation](https://gitlab.com/iggydv12/nomad/-/wikis/Home)

## Known Issues

- Nomad's overlay storage component TomP2P does not function on Windows 11 or macOS X
