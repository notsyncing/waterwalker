# WaterWalker

Lightweight single-threaded asynchronize TCP server using Java NIO, only a ~9KB jar with no dependencies!
 
## Motivation

I just want a small and simple tcp server in my project, but all I found was some huge things like Netty, 
so I created this small library.

## Requirements

Java 8
  
## Install

Available in bintray:

```groovy
repositories {
    maven { url "http://dl.bintray.com/notsyncing/maven" }
}

dependencies {
    compile "io.github.notsyncing.waterwalker:waterwalker:0.1.0"
}
```

You may replace `0.1.0` to the newest version.

## Usage

Please see a test application in `waterwalker-test` folder.
This application is also used for performance test.

Basic usage:

```java
// Create a server at 8080 port
WaterWalkerServer server = new WaterWalkerServer("0.0.0.0", 8080);

// Set incoming data handler
server.setDefaultReadCallback((channel, buf) -> {
    // Read all data until connection closed by peer or 100 bytes reached
    server.readUntilClose(channel, buf, 0, 100, (data, ex) -> {
        // Process data(ByteBuffer) or exception(Exception)
    });
});

// Stop server before exit
server.stop();
```

The source contains javadoc for more usages.

## Performance

Environment:

```
Intel Core i7-2670QM 2.20GHz
16GB DDR3 1600MHz (2x8GB)

Ubuntu 17.04 amd64, kernel 4.10.0-26-generic

openjdk version "1.8.0_131"
OpenJDK Runtime Environment (build 1.8.0_131-8u131-b11-0ubuntu1.17.04.1-b11)
OpenJDK 64-Bit Server VM (build 25.131-b11, mixed mode)
```

Command: 

`siege -c100 -r1000 -b http://localhost:8080/index.html`

Result:

```
** SIEGE 4.0.2
** Preparing 100 concurrent users for battle.
The server is now under siege...
Transactions:		      100000 hits
Availability:		      100.00 %
Elapsed time:		       20.82 secs
Data transferred:	        2.29 MB
Response time:		        0.02 secs
Transaction rate:	     4803.07 trans/sec
Throughput:		        0.11 MB/sec
Concurrency:		       91.04
Successful transactions:      100000
Failed transactions:	           0
Longest transaction:	        3.25
Shortest transaction:	        0.00
```