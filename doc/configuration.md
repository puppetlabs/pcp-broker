# Configuring a pcp-broker instance

Here we'll walk you through the required services and configuration
files needed to set up a pcp-broker listening on `wss://0.0.0.0:8142/pcp`

## Service dependencies

In order to use the pcp-broker you will need to bootstrap a number of
dependant trapperkeeper services - a Webserver service, a Webrouting
service, a Status service, and a Metrics service.

```
# bootstrap.cfg
puppetlabs.pcp.broker.service/broker-service
puppetlabs.trapperkeeper.services.authorization.authorization-service/authorization-service
puppetlabs.trapperkeeper.services.webrouting.webrouting-service/webrouting-service
puppetlabs.trapperkeeper.services.webserver.jetty9-service/jetty9-service
puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-service
puppetlabs.trapperkeeper.services.status.status-service/status-service
```

## Service configuration


The authorization subsystem will need to be configured following the notes on
mapping messages to ring requests in [authentication](authentication.md) and
the notes on how to [configure trapperkeeper-authorization](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md).

To disable all authorization you will need a null policy like so:

```
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "no limits"
      match-request: {
        path: "^/"
        type: regex
      }
      sort-order: 1
      allow-unauthenticated: true
    }
  ]
}
```

The webserver needs to be configured for ssl against the puppet CA for
your install (see [authentication](authentication.md)), with
[client-auth](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#client-auth)
set to `need` or `want`

```
# webserver.conf
webserver: {
    client-auth want
    ssl-port 8142
    ssl-host 0.0.0.0
    ssl-key /var/lib/puppet/ssl/private_keys/broker.example.com.pem
    ssl-cert /var/lib/puppet/ssl/certs/broker.example.com.pem
    ssl-ca-cert /var/lib/puppet/ssl/ca/ca_crt.pem
    ssl-crl-path /var/lib/puppet/ssl/ca/ca_crl.pem
}
```

The brokers protocol handlers and the status service will need to be mounted using a
[webrouting](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/webrouting-config.md)
configuration.

```
web-router-service: {
    "puppetlabs.trapperkeeper.services.status.status-service/status-service": "/status"
    "puppetlabs.pcp.broker.service/broker-service": {
       v1: "/pcp"
       # vNext endpoint will need to be enabled with pcp-broker.protocol-vnext property
       vNext: "/pcp/vNext"
    }
}
```

The broker itself will need to be configured with a spool directory
and some upper bounds on accept/delivery threads.

```
# pcp-broker.conf
pcp-broker: {
    ## A path where in-flight messages will be persisted - required
    broker-spool /var/lib/pcp-broker

    ## Number of consumers for the accept queue.  Default is 4
    # accept-consumers = 4

    ## Number of consumers for the delivery queue.  Default is 16
    # delivery-consumers = 16

    ## Enable the vNext protocol (needs corresponding webroute).  Default is false
    # protocol-vnext = false
}
```
