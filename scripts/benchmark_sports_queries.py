#!/usr/bin/env python3
"""Exercise the actual Sports DAO SQL on a deterministic large, offline Room-schema fixture.

Row bounds and indexed catalogue lookup are assertions; host times are diagnostic only.
"""
import json
from pathlib import Path
import sqlite3
import time

ROOT = Path(__file__).resolve().parents[1]


def query(name):
    source = (ROOT / "app/src/main/java/app/opentv/sports/SportsDao.kt").read_text()
    end = source.index(f"fun {name}(")
    start = source.rfind('@Query("""', 0, end) + len('@Query("""')
    return source[start:source.index('""")', start)]


def fixture(channels=2000):
    schema_dir = ROOT / "app/schemas/app.opentv.data.db.OpenTvDatabase"
    schema = json.loads(max(schema_dir.glob("*.json"), key=lambda p: int(p.stem)).read_text())["database"]
    db = sqlite3.connect(":memory:")
    entities = {e["tableName"]: e for e in schema["entities"]}
    for name, entity in entities.items():
        db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity["indices"]:
            db.execute(index["createSql"].replace("${TABLE_NAME}", name))

    def insert(table, **values):
        fields = [f["columnName"] for f in entities[table]["fields"]]
        statement = f"INSERT INTO {table} (" + ",".join(f'`{f}`' for f in fields) + ") VALUES (" + ",".join("?" for _ in fields) + ")"
        db.execute(statement, [values.get(f) for f in fields])

    now = 1_900_000_000_000
    for source in range(1, 5):
        insert("sources", id=source, name=f"Provider {source}", kind="M3U", url="https://example.invalid",
               userAgent="Test", liveFormat="HLS", enabled=1, lastCatalogSyncMillis=now)
    insert("epg_feeds", id=1, name="Fixture", builtIn=0, enabled=1, lastSyncMillis=now, lastResult="")
    for number in range(channels):
        event = f"fixture:{number // 20}"
        if number % 20 == 0:
            insert("sports_events", id=event, sportId="hockey", leagueId="nhl", homeTeamId=f"home-{number}",
                   awayTeamId=f"away-{number}", homeName=f"Team A {number}", awayName=f"Team B {number}",
                   title=f"Fixture Game {number}", startUtcMillis=now + number * 60_000,
                   endUtcMillis=now + number * 60_000 + 10_800_000, status="SCHEDULED", source="epg",
                   sourceEventId=str(number), verified=0, updatedAtMillis=now)
        insert("channels", id=number+1, sourceId=number % 4 + 1, streamId=str(number), name=f"Channel {number}",
               displayName=f"Channel {number}", groupKey="same-logical-channel", qualityRank=0, qualityLabel="",
               epgChannelId=f"epg-{number}", tvArchive=0, tvArchiveDays=0, streamUrl="https://example.invalid",
               favourite=0, hidden=0, sortIndex=number, lastSeenMillis=now, syncTouched=0)
        start = now + (number // 20 * 20) * 60_000
        insert("sports_programmes", key=f"programme-{number}", eventId=event, feedId=1, epgChannelId=f"epg-{number}",
               startUtcMillis=start, endUtcMillis=start + 10_800_000, title=f"Fixture Game {number // 20 * 20}", confidence="CONFIRMED")
    # Non-sports guide load does not expand event details or materialize entire catalogues.
    db.executemany("INSERT INTO programmes (feedId,epgChannelId,startUtcMillis,endUtcMillis,title) VALUES (?,?,?,?,?)", (
        (1, f"epg-{c}", now + slot * 1_800_000, now + (slot+1) * 1_800_000, f"Fixture programme {c}-{slot}")
        for c in range(channels) for slot in range(48)))
    db.commit()
    return db, now


def validate(channels=2000):
    db, now = fixture(channels)
    params = dict(from_=now, until=now + 604_800_000, pattern="%", limit=40, excludedCategories="||", eventId="fixture:0", offset=0, filter="ALL", now=now,
                  dayFrom=now, dayUntil=now+86_400_000, league=None, team=None, sport=None)
    params["from"] = params.pop("from_")
    started = time.perf_counter()
    cards = db.execute(query("observeCards"), params).fetchall()
    streams = db.execute(query("observeStreams"), params).fetchall()
    assert len(cards) == min(40, (channels+19)//20), len(cards)
    assert len(streams) == min(channels, 20), len(streams)
    assert all(row[-1] == min(channels, 20) for row in cards), "Provider choices were collapsed"
    plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + query("observeStreams"), params)]
    assert any("index_channels_epg" in row or "index_channels_matchedEpgId" in row for row in plan), plan
    batch = db.execute(query("programmeBatch"), dict(feedId=1, afterId=0, **{"from": now, "until": now+604_800_000})).fetchall()
    assert len(batch) <= 500
    result = dict(channels=channels, programmes=channels*48, cards=len(cards), streams=len(streams), batch=len(batch),
                  elapsed_seconds=round(time.perf_counter()-started, 3))
    db.close()
    return result


if __name__ == "__main__":
    print(json.dumps(validate(), indent=2))
