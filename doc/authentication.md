# Authentication

As discussed in greater depth in the PCP specifications PCP
communications are secured and authenticated using the client
certificates from a standard puppet x509 certificate authority.

In case a certificate bundle is used, pcp-broker will use the first certificate
of the chain.

If you need to set up some certificates for testing, you can use the
following recipes to create some certs:

## `lein certs`

From a source checkout, blow away the existing test-resources/ssl
directory, then invoke `lein certs` with a set of identities to
generate:

    lein certs broker.example.com client01.example.com client02.example.com


## With puppet cli

Alternatively, you can use the `puppet` binary to achieve much the
same with some shell magic:

    puppet master --ssldir=`pwd`/test-resources/ssl
    puppet cert --ssldir=`pwd`/test-resources/ssl generate broker.example.com
    puppet cert --ssldir=`pwd`/test-resources/ssl generate pcp-controller
    for i in $(seq 0 3) ; do
        puppet cert --ssldir=`pwd`/test-resources/ssl generate $(printf "client%02d.example.com" $i)
    done
