# Configuring a pcp-broker instance

Here we'll walk you through the required services and configuration
files needed to set up a pcp-broker listening on `wss://0.0.0.0:8142/pcp`

## Service dependencies

In order to use the pcp-broker you will need to bootstrap a number of
dependant trapperkeeper services - a Webserver service, a Webrouting
service, and a Metrics service.

```
# bootstrap.cfg
puppetlabs.pcp.broker.service/broker-service
puppetlabs.trapperkeeper.services.webrouting.webrouting-service/webrouting-service
puppetlabs.trapperkeeper.services.webserver.jetty9-service/jetty9-service
puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-service
```

## Service configuration

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

The broker will need to be mounted at a path using a
[webrouting](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/webrouting-config.md)
configuration.

```
web-router-service: {
    "puppetlabs.pcp.broker.service/broker-service": {
       websocket: "/pcp"
       metrics: "/"
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
}
```
