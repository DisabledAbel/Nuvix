#!/usr/bin/env python3
"""Run the current bounded EPG search against a deterministic host SQLite fixture.

This diagnostic needs only Python's standard library. It is not a TV benchmark or
a CI timing threshold; Android's SQLite version, storage and CPU differ.
"""

import argparse
import json
from pathlib import Path
import sqlite3
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channels", type=int, default=2000)
    parser.add_argument("--programmes-per-channel", type=int, default=48)
    args = parser.parse_args()
    if args.channels < 1 or args.programmes_per_channel < 1:
        parser.error("fixture sizes must be positive")

    root = Path(__file__).resolve().parents[1]
    schemas = root / "app/schemas/app.opentv.data.db.OpenTvDatabase"
    latest = max(schemas.glob("*.json"), key=lambda path: int(path.stem))
    schema = json.loads(latest.read_text())["database"]
    db = sqlite3.connect(":memory:")
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity["indices"]:
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    db.execute("""INSERT INTO sources
        (id,name,kind,url,username,password,macAddress,epgUrl,userAgent,liveFormat,enabled,lastCatalogSyncMillis)
        VALUES (1,'Fixture','M3U','https://example.invalid',NULL,NULL,NULL,NULL,'Test','HLS',1,0)""")
    db.execute("""INSERT INTO epg_feeds
        (id,name,url,providerSourceId,builtIn,enabled,lastSyncMillis,lastResult)
        VALUES (1,'Guide',NULL,NULL,0,1,0,'')""")
    entity = next(item for item in schema["entities"] if item["tableName"] == "channels")
    columns = [field["columnName"] for field in entity["fields"]]
    insert = "INSERT INTO channels(" + ",".join(columns) + ") VALUES (" + ",".join("?" for _ in columns) + ")"
    now = 1_900_000_000_000
    for channel in range(args.channels):
        values = dict(
            id=channel + 1, sourceId=1, streamId=str(channel), name=f"Channel {channel}",
            displayName=f"Channel {channel}", groupKey=f"channel{channel}", qualityRank=0,
            qualityLabel="", epgChannelId=f"epg-{channel}", tvArchive=0, tvArchiveDays=0,
            streamUrl="https://example.invalid", favourite=0, hidden=0, sortIndex=channel,
            lastSeenMillis=0, syncTouched=0,
        )
        db.execute(insert, [values.get(column) for column in columns])
    db.executemany("""INSERT INTO programmes
        (id,feedId,epgChannelId,startUtcMillis,endUtcMillis,title) VALUES (?,?,?,?,?,?)""", (
        (channel * args.programmes_per_channel + programme + 1, 1, f"epg-{channel}",
         now + programme * 1_800_000, now + (programme + 1) * 1_800_000, f"Fixture {channel}")
        for channel in range(args.channels) for programme in range(args.programmes_per_channel)
    ))
    source = (root / "app/src/main/java/app/opentv/data/db/Daos.kt").read_text()
    start = source.index("SELECT p.*, MIN(c.id) AS channelId FROM channels c")
    end = source.index('""")', start)
    query = source[start:end]
    params = dict(pattern="%Fixture%", filter="ALL", nowUtcMillis=now,
                  untilUtcMillis=now + 604_800_000, excludedCategories="||", limit=200)
    started = time.perf_counter()
    result = db.execute(query, params).fetchall()
    elapsed = time.perf_counter() - started
    print(f"Host SQLite {sqlite3.sqlite_version}; Room schema v{schema['version']}")
    print(f"{args.channels} channels; {args.channels * args.programmes_per_channel} programmes")
    print(f"Search returned {len(result)} rows in {elapsed:.3f}s (host diagnostic only)")
    print("Query plan:")
    for row in db.execute("EXPLAIN QUERY PLAN " + query, params):
        print(f"  {row[3]}")
    db.close()


if __name__ == "__main__":
    main()
