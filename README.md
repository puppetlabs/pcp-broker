# cthun

A message broker implementing the cthun wire protocol

# Setting up a test/demo environment

We'll build a 1 x Server, 3 x Agent, 1 x Controller environment.

## SSL

We'll need to create ssl certificates.  The default paths in
test-resources/conf.d/cthun.conf assume a puppet ca in test-resources/ssl
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

Checkout and build https://github.com/puppetlabs/pegasus according to its
instructions.

Use by symlinking the test-resources/ssl from your cthun checkout into the pegasus
checkout as test-resources.

    ln -s ../cthun/test-resources/ssl test-resources

Pegasus requires some config (default config file is ~/.pegasus in JSON) for Cthun

    {
      "cthun" : {
        "server" : "wss://127.0.0.1:8090/cthun/",
        "ca" : "[CTHUN REPO]/test-resources/ssl/ca/ca_crt.pem",
        "cert" : "[CTHUN REPO]/test-resources/ssl/certs/0000_controller.pem",
        "key" : "[CTHUN REPO]/test-resources/ssl/private_keys/0000_controller.pem"
      }
    }

And send a ping via the middleware to all agents

    ./bin/pegasus ping

Or invoke an rpc action

    ./bin/pegasus rpc reverse hash input=foo


# Random notes

## Message flow

Websocket -> Accept Queue -> Address Expansion -> Delivery Queue ->
Websocket (or Delivery Queue on failure)
