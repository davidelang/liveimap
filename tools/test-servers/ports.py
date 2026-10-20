import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def container_port(server, service):
    """Integer container listen port from servers.tsv. A missing row exits 1."""
    path = os.path.join(HERE, "servers.tsv")
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 3 or not fields[2].isdigit():
                continue
            if fields[0] == server and fields[1] == service:
                return int(fields[2])
    sys.exit("no servers.tsv row for %s %s" % (server, service))
