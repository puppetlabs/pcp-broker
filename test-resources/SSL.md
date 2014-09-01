You'll need to create a test ca and some identities.

It's easy enough, just do the following:

    puppet master --ssldir=`pwd`/ssl
    puppet cert --ssldir=`pwd`/ssl generate cthun-server
    puppet cert --ssldir=`pwd`/ssl generate cthun-client

Then you can verify the client auth is in play with:

    openssl s_client -connect localhost:8090

vs

    openssl s_client -connect localhost:8090 \
        -cert ssl/certs/cthun-client.pem \
        -key ssl/private_keys/cthun-client.pem \
        -CAfile ssl/ca/ca_crt.pem

The first should not stay connected as we've configured to :need
client auth.  Later we may change this to be :want so that we can just
offer /stats/ or other visibility tools without needing client auth.
