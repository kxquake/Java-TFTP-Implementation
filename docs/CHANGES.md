# Java TFTP: essential cleanup record

Date: 20 October 2026. Baseline: the actual source in
`265842-Computer-Networks-TFTP.zip`. All edits are in a separate publication copy.
The source archive and untouched extraction are preserved; nothing was published.

## Scope and compatibility change announced in advance

The four Java projects, classes, command-line menus, UDP packet layout, block size,
default ports and thread-per-transfer design remain. No dependency, GUI, framework,
new package hierarchy, parent build or authentication feature was introduced.
Java 17 is the original POM target; tests compiled with `--release 17` on JDK 25.0.2.

**The necessary TCP protocol correction adds a two-byte payload-length field to
DATA frames.** This was specified before editing the protocol. Both TCP peers
must be updated together; they are not compatible with the old TCP binaries.
UDP's standard-style packet format remains unchanged. The existing TCP ACK design
is retained and made consistent, not silently replaced with a new transport design.

For scale: the nine existing Java source files changed by 272 added and 92 removed
lines. The extra test runner, README and refreshers are separate from those counts.
Most source additions are validation, safe output cleanup, retry/state guards and
the TCP length field/readFully repair. Exact diffs and per-file SHA-256 manifests
accompany the ZIP in the outputs folder.

## Every source-file change

### `TFTP-UDP-Client/src/main/java/TFTPClient.java`

Changed only the default address prompt/fallback from `localhost` to `127.0.0.1`,
matching the new safe loopback server binding even on machines with different
IPv6 localhost preferences. Menu choices, filename prompts, transfer calls and
socket cleanup remain.

### `TFTP-UDP-Client/src/main/java/TFTPConstants.java`

The null-terminated string reader now checks buffer bounds and throws a controlled
IllegalArgumentException for missing terminators instead of reading past the
buffer. Byte-to-character conversion is unsigned. Existing port 1069, block size
512, three-second timeout, five-attempt limit and opcodes remain.

### `TFTP-UDP-Client/src/main/java/TFTPPacket.java`

- Request filename bytes are explicitly ASCII, with nonempty/length/NUL/encoding
  validation, avoiding byte-count/allocation mismatches for non-ASCII names.
- DATA/ACK/ERROR parsing validates the minimum/maximum received length before
  reading headers. ACK must have exactly four bytes.
- Existing packet classes, opcode values and DATA/ACK wire layouts are retained.

### `TFTP-UDP-Client/src/main/java/TFTPTransfer.java`

- Saves the last sent packet and actually retransmits it on timeout; the original
  retry loops mostly waited again without resending the request/DATA.
- Validates the sender address and expected block/type before recording the first
  transfer port. Connects the UDP socket to that peer afterward, rejecting traffic
  from unrelated addresses/ports rather than letting it change state.
- Ignores malformed responses and re-ACKs an already received prior DATA block.
- Uses unsigned block comparisons and 16-bit wrapping to avoid signed-short failure
  after block 32767 and incorrect counters beyond the wire's 16-bit range.
- Uses an extra receive byte so oversized packets can be detected rather than
  silently truncated into apparently valid packets.
- Downloads refuse existing local files before sending RRQ; output uses exclusive
  CREATE_NEW instead of truncating an existing file. A handled failed download
  removes only the newly created partial output.
- After final DATA, waits up to six seconds to re-ACK one retransmission before
  closing. The bounded dally ceiling is explicitly marked in code.
- Existing read/write methods, binary streams, 512-byte blocks, ACK logic and
  already-correct final-empty upload rule remain.

### `TFTP-UDP-Server/src/main/java/TFTPProtocol.java`

- Parses the actual supplied datagram bytes with header/bounds checks, rather than
  assuming a large zero-padded buffer is a complete request.
- Requires a nonempty filename, RRQ/WRQ opcode, terminated strings and octet mode.
- DATA/ACK parser uses the received length, validates opcode/size and rejects
  malformed ACKs. Error strings use explicit ASCII encoding.
- Existing Request/DataPacket classes and packet creation layouts remain.

### `TFTP-UDP-Server/src/main/java/TFTPServer.java`

- Binds to 127.0.0.1 by default, with optional `[port] [bind-address]` arguments for
  isolated tests or deliberate LAN use. Port 1069 remains the default.
- Tracks active client endpoints with a small concurrent set. A repeated initial
  request during an active transfer no longer creates another transfer socket or
  writer; the existing handler's retry logic responds. The entry is removed when
  its handler exits. Different client endpoints can still transfer concurrently.
- Existing listener/cached executor/per-client handler architecture remains.

### `TFTP-UDP-Server/src/main/java/ClientHandler.java`

- Parses only the initial datagram's actual offset/length and returns an error for
  malformed requests rather than allowing unchecked buffer errors.
- Connects each transfer socket to the initiating client; unrelated packets cannot
  satisfy that transfer's ACK/DATA waits.
- Canonicalises server files and restricts names to basenames in the working
  directory. Rejects traversal, absolute/path-separated names, Windows colons,
  empty/dot names, excessive length and symlink targets outside the root.
- RRQ now sends a final short/empty DATA block, including for empty files and exact
  multiples of 512. The original server sent nothing for an empty file and omitted
  the required terminator for full-block files.
- Validates actual ACK lengths/unsigned block numbers. Retry counters also advance
  on invalid responses rather than looping indefinitely on them.
- WRQ uses exclusive file creation, resets the reusable datagram's capacity before
  each receive, detects oversize packets, re-ACKs duplicate prior DATA and wraps
  sequence numbers. These prevent truncation/duplicate-write/state errors.
- Handled failed uploads close and remove their own partial output. Successful
  final DATA briefly retains ACK availability for one retransmission without writing
  the bytes twice. The six-second/bounded dally behaviour is explicit in code/docs.

### `TFTP-TCP-Client/src/main/java/TFTPClientTCP.java`

- Default host is 127.0.0.1 to match the loopback server.
- Request filenames and decoded server messages use explicit UTF-8.
- DATA receives read unsigned block and length fields, validate length <=512, then
  use `readFully()` instead of treating one stream read as a whole message.
- DATA sends include the new unsigned two-byte length field. Empty files and exact
  multiples send a zero-length terminal frame instead of silently ending/hanging.
- Uses unsigned/wrapped block handling, a 15-second socket read timeout, and a
  bound on null-terminated server messages.
- Download destinations use CREATE_NEW/no overwrite and remove their own output
  after handled failure. Existing interactive menu, request/ACK/error formats and
  matching per-block ACK exchange remain.

### `TFTP-TCP-Server/src/main/java/TFTPServerTCP.java`

- Default bind is loopback; optional bind address follows the existing optional
  port. Accepted sockets have a 15-second read timeout; the executor shuts down
  when the listener exits.
- Applies the same canonical-directory/basename restriction as UDP, and bounds
  null-terminated request strings. Request/error strings use UTF-8.
- Missing RRQ files return an ERROR while the stream is still open, rather than
  trying to write the response only after try-with-resources closed it.
- RRQ emits length-framed blocks including empty terminators and consumes/validates
  the ACKs the original client already sent. Previously those ACKs could accumulate
  while stream parsing mistook following headers for file bytes.
- WRQ reads exact advertised payload lengths with `readFully()`, validates size/
  block numbers, wraps unsigned sequence state, uses CREATE_NEW and cleans up its
  own handled partial uploads.
- The main class/private per-connection handler and thread-pool design remain.

## Build, documentation and test files

- `TFTP-UDP-Server/pom.xml`: repaired the original zero-byte file with the same
  minimal Java-17 Maven properties/group/version style as the other modules. No
  dependency or plugin suite was added. The other three POMs are unchanged.
- Root `.gitignore`: excludes IDE state, output/build directories, `.class` files,
  logs, Python caches, OS files and the documented `runtime/` demo workspace.
- Root `README.MD` replaced/normalised to `README.md`: complete build/run commands,
  separate work-directory setup, ports, safety boundary, protocol/framing details,
  tests and compatibility warning. The old README stopped after its structure heading.
- `tests/smoke.py`: added one standard-library Python runner. It compiles all four
  modules and runs real loopback clients/servers in temporary directories, creates
  its own binary fixtures and terminates only its own child servers.
- `docs/PROJECT-REFRESHER.md`: architecture, UDP/TCP flows, actual source/report
  differences, interview explanation and honest limitations.
- `docs/CHANGES.md`: this complete record.

## Excluded from publication, retained in the original

- All root/module `.idea/` directories, including machine paths and editor workspace
  history; root `out/`; every module `target/`; all compiled `.class` files.
- `TFTP_Report.pdf`: the original assessment report/screenshots are retained locally,
  not presented as test evidence for the changed TCP framing or current interoperability.
- Root `test123` and both `harrypotter.txt` copies in the UDP modules. These large
  book-like test payloads are unnecessary for running/testing the implementation
  and have no recorded redistribution licence. No replacement literary/media asset
  was introduced: the test runner generates arbitrary bytes at runtime.
- Empty directory scaffolding does not need ZIP entries. The Java sources and
  POM-based layout remain sufficient to compile/import the modules.

The original two TCP `sample.txt` files and four nonempty module `.gitignore`
files are retained unchanged. No original archive contents were destroyed.

## Verification and boundaries

The final test runner passes these checks:

1. All four modules compile with `javac --release 17` using JDK 25.0.2.
2. Actual Java client/server upload **and** download for both transports at sizes
   0, 1, 511, 512, 513, 1024 and 4097 bytes; generated binary contents match exactly.
3. Missing-file responses and refusal to overwrite existing local files.
4. Server rejection of parent/absolute/Windows-colon path requests, unsupported
   UDP mode, a truncated request and an existing remote upload destination.
5. TCP upload with every header/payload byte sent separately: exact reconstruction.
6. Truncated TCP upload: its partial output is removed after the handled disconnect.
7. UDP client retransmission after a dropped initial request and dropped DATA ACK.
8. UDP server DATA retransmission and duplicate-final-DATA ACK without duplicate bytes.
9. Duplicate initial UDP WRQ keeps the original active transfer port/writer and
   receives the existing handler's ACK retry instead of a spurious file-exists error.
10. A malformed short UDP DATA packet followed by a valid 512-byte block does not
    shrink the reused packet buffer or truncate the upload.

This is not a claim of complete RFC 1350 conformance, third-party certification,
all-loss/reordering resilience or public-service hardening. The old report's
third-party screenshots were not re-run against this publication version. Maven
import/package execution was not tested because the checks used the JDK directly.

## Limits intentionally left as separate upgrades

No authentication/encryption was added. The default loopback binding limits exposure,
but an explicitly LAN-bound server is still an unauthenticated file service.
Use a directory containing only intended demo files, not your repository/home
directory. Cached thread pools, storage quotas and hostile-network resource limits
remain original limitations. GUI/CLI redesign and broader input ergonomics were
not part of the essential fixes.

The final-ACK dally is bounded to one retransmission, not an unlimited loss-recovery
loop. A force-killed process may leave partial output because Java finally blocks
cannot run; handle your own demo files accordingly. The test runner does not claim
full 65,536-block stress coverage, although comparisons/counters now correctly use
the wire's unsigned 16-bit range.

The original TCP report's “no ACK” narrative did not match its source. This cleanup
documents the actual retained ACK design and the necessary framing change, rather
than upgrading it into a different protocol unnoticed. Any later removal of TCP
ACKs, packet-library refactor, new secure transport or GUI should be a separate decision.
