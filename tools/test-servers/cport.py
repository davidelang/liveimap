#!/usr/bin/env python3
import sys
import ports

if len(sys.argv) != 3:
    sys.exit("usage: cport.py server service")
print(ports.container_port(sys.argv[1], sys.argv[2]))
