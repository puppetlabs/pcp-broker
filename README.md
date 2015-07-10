# cthun

A message broker implementing the cthun wire protocol

# Setting up a test/demo environment

We'll build a 1 x Server, 3 x Agent, 1 x Controller environment.

## SSL

We'll need to create ssl certificates.  The default paths in
test-resources/config.ini assume a puppet ca in test-resources/ssl
and the that cn of the cthun server will be `cthun-server`.  To
make these run the following:

    puppet master --ssldir=`pwd`/test-resources/ssl
    puppet cert --ssldir=`pwd`/test-resources/ssl generate cthun-server
    puppet cert --ssldir=`pwd`/test-resources/ssl generate cthun-client
    for i in $(seq 0 3) ; do
        puppet cert --ssldir=`pwd`/test-resources/ssl generate $(printf "%04d_agent" $i)
        puppet cert --ssldir=`pwd`/test-resources/ssl generate $(printf "%04d_controller" $i)
    done

For simple use cases you may be able to get away with:

    lein certs

## Running the server

    lein tk


## Running the agents

Checkout and build https://github.com/puppetlabs/cthun-agent according to its
instructions.

Start with

    ./bin/cthun-agent \
        --ca   ../cthun/test-resources/ssl/ca/ca_crt.pem \
        --cert ../cthun/test-resources/ssl/certs/0000_agent.pem \
        --key  ../cthun/test-resources/ssl/private_keys/0000_agent.pem

You can start additional agents with the identities {0001..0003}\_agent by
adjusting that.

## Running a controller

Checkout and build https://github.com/puppetlabs/pegasus cthun_connector
branch according to its instructions.

Use by symlinking the test-resources/ssl from your cthun checkout into the pegasus
checkout as test-resources.

    ln -s ../cthun/test-resources/ssl test-resources

And send a ping via the middleware to all agents

    ./bin/pegasus ping

Or invoke an rpc action

    ./bin/pegasus rpc reverse hash message=foo


# Random notes

## Message flow

Websocket -> Accept Queue -> Address Expansion -> Delivery Threadpool ->
Websocket (or Redelivery Queue)

Redelivery Queue -> Websocket (or Redelivery Queue)

### Choosing inventory

In bootstrap.cfg you will need one of the following inventory-services
(it's imagined puppetdb may be an inventory service in future)

The `in-memory` inventory service does not persist any storage to disk.

    puppetlabs.cthun.inventory.in-memory/inventory-service

### Choosing meshing

In booststrap.cfg (you cannot put comments in bootstrap.cfg) you will
need one of the following meshing-services

The `in-memory` meshing service does no meshing.

    puppetlabs.cthun.meshing.in-memory/meshing-service
    puppetlabs.cthun.meshing.hazelcast/meshing-service
