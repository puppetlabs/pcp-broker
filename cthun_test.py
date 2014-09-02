#!/usr/bin/env python

'''
WebSocket client based on ws4py

Initial design inspired by:
https://ws4py.readthedocs.org/en/latest/sources/clienttutorial/

Another library Autobahn worth checking:
http://autobahn.ws/python/
'''

import json

import os
import sys
import time
import logging
import argparse
from collections import namedtuple

try:
    from ws4py.client import WebSocketBaseClient
    from ws4py.manager import WebSocketManager
    from ws4py import format_addresses, configure_logger
except ImportError:
    print("ws4py is required to run cthun_ws_test "
          "(try 'sudo pip install ws4py')")
    sys.exit(1)


# Tokens

CONNECTION_CHECK_INTERVAL = 2      # [s]
SEND_INTERVAL             = 0.001  # [s]


# Globals

logger = None


# Errors

class RequestError(Exception):
    ''' Exception due to an invalid request '''
    pass


# Client

class EchoClient(WebSocketBaseClient):
    def __init__(self, url, mgr, ca_certs = None, keyfile = None, certfile = None):
        self._mgr = mgr
        WebSocketBaseClient.__init__(self, url, ssl_options = {
            "ca_certs" : ca_certs,
            "keyfile"  : keyfile,
            "certfile" : certfile })

    def handshake_ok(self):
        logger.info("Opening %s" % format_addresses(self))
        self._mgr.add(self)

    def received_message(self, msg):
        logger.info("### Received a message: %s" % msg)



# Configuration

ScriptOptions = namedtuple('ScriptOptions', ['url',
                                             'concurrency',
                                             'interval',
                                             'verbose',
                                             'message',
                                             'json_file',
                                             'num',
                                             'ca',
                                             'key',
                                             'cert'])

SCRIPT_DESCRIPTION = '''WebSocket client to execute load tests.'''

def parseCommandLine(argv):
    # Define
    parser = argparse.ArgumentParser(description = SCRIPT_DESCRIPTION)
    parser.add_argument("url",
                        help = "server url (ex. ws//:localhost:8080/hello)"),
    parser.add_argument("concurrency",
                        help = "number of concurrent connections")
    parser.add_argument("-m", "--message",
                        help = "message to be sent to the server",
                        default = None)
    parser.add_argument("-j", "--json_file",
                        help = "file containing the json message to be sent",
                        default = None)
    parser.add_argument("-n", "--num",
                        help = "number of messages to be sent",
                        default = 1)
    parser.add_argument("-i", "--interval",
                        help = "connection check interval; default: %d s"
                               % CONNECTION_CHECK_INTERVAL,
                        default = CONNECTION_CHECK_INTERVAL)
    parser.add_argument("--ca",
                        help = "ca pem",
                        default = None)
    parser.add_argument("--key",
                        help = "client key",
                        default = None)
    parser.add_argument("--cert",
                        help = "client cert",
                        default = None)
    parser.add_argument("-v", "--verbose",
                        help = "verbose",
                        action = "store_true")

    # Parse and validate
    args = parser.parse_args(argv[1:])

    try:
        concurrency = int(args.concurrency)
        if concurrency < 1:
            raise ValueError()
    except ValueError:
        raise RequestError('concurerncy must be a positive integer')

    if not args.url.startswith("ws"):
        raise RequestError('invalid url')

    try:
        interval = int(args.interval)
        if interval < 0:
            raise ValueError()
    except ValueError:
        raise RequestError('interval must be a positive integer')

    abs_json_file = None
    if args.json_file:
        abs_json_file = os.path.abspath(args.json_file)
        if not os.path.isfile(abs_json_file):
            raise RequestError("%s does not exist" % abs_json_file)

    num = None
    if any([args.message, args.json_file]):
        try:
            num = int(args.num)
            if num < 0:
                raise ValueError()
        except ValueError:
            raise RequestError('invalid number of messages')
    else:
        num = 0

    return ScriptOptions(args.url, concurrency, interval, args.verbose,
                         args.message, abs_json_file, num, args.ca, args.key, args.cert)


def getJsonFromFile(file_path):
    with open(file_path) as f:
        json_content = f.read()
    return json.read(json_content.strip())

def getMessage(script_options):
    if script_options.json_file is not None:
        return getJsonFromFile(script_options.json_file)
    return script_options.message


# The actual script

def run(script_options):
    global logger
    level  = logging.DEBUG if script_options.verbose else logging.INFO
    logger = configure_logger(level = level)

    mgr = WebSocketManager()

    try:
        mgr.start()
        clients = []

        # Connect
        for connection_idx in range(script_options.concurrency):
            client = EchoClient(script_options.url, mgr,
                                script_options.ca, script_options.key, script_options.cert)
            client.connect()
            clients.append(client)

        logger.info("%d clients are connected" % (connection_idx + 1))

        # Send
        msg = getMessage(script_options)
        if msg:
            msg = json.write(msg)
            logger.info("Sending messages (num=%d):\n%s", script_options.num, msg)
            for client in clients:
                for _ in range(script_options.num):
                    client.send(msg)
                    time.sleep(SEND_INTERVAL)
            logger.info("Done sending")

        # Sleep before disconnecting
        logger.info("Sleeping for %d s before disconnecting",
                    script_options.interval)
        time.sleep(script_options.interval)

    except KeyboardInterrupt:
        logger.info("Interrupted by user")
    finally:
        logger.info("Disconnecting!")
        mgr.close_all(code    = 1000,
                      message = "Client is closing the connection")
        mgr.stop()
        mgr.join()


def main(argv = None):
    if argv is None:
        argv = sys.argv

    try:
        script_options = parseCommandLine(argv)
        print script_options
        run(script_options)
    except RequestError as e:
        print("Invalid request: %s" % e)
        return 1
    except Exception as e:
        logger.exception(str(e))
        return 1

    return 0


if __name__ == '__main__':
    sys.exit(main())
