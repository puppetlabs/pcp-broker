#!/usr/bin/env python3

import re
import sys
from collections import namedtuple

LogEntry = namedtuple('LogEntry', ['datetime',
                                   'validation_outcome',
                                   'remote_address',
                                   'common_name',
                                   'source',
                                   'message_type',
                                   'message_id',
                                   'destination'])

entry_regex = re.compile(r'^(?P<date_time>\[\S+\s?\S+\])\s(?P<outcome>\S+)'
                         '\s(?P<remote_addr>\S+)\s(?P<cn>\S+)\s(?P<src>\S+)'
                         '\s(?P<type>\S+)\s(?P<id>\S+)\s(?P<targets>.*)$')


def parse_entry(line):
    entry_obj = entry_regex.search(line)

    if not entry_obj:
        raise Exception("bad line: %s" % line)

    return LogEntry(datetime=entry_obj.group('date_time'),
                    validation_outcome=entry_obj.group('outcome'),
                    remote_address=entry_obj.group('remote_addr'),
                    common_name=entry_obj.group('cn'),
                    source=entry_obj.group('src'),
                    message_type=entry_obj.group('type'),
                    message_id=entry_obj.group('id'),
                    destination=entry_obj.group('targets'))


def main():
    if len(sys.argv) != 2:
        print("messages_log_parser must be invoked with the log file path")
        return 1

    file_path = sys.argv[1].strip()

    def _readEntry(file_handler):
        for line in filter(None, (l.rstrip() for l in f_r)):
            try:
                yield parse_entry(line)
            except Exception as e:
                print(e)

    with open(file_path) as f_r:
        for entry in _readEntry(f_r):
            print(entry)

    return 0


if __name__ == "__main__":
    sys.exit(main())
