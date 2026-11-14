import uuid, datetime, sys
for s in sys.argv[1:]:
    u = uuid.UUID(s)
    t = (u.time - 0x01b21dd213814000) / 1e7
    print(s, datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat())
