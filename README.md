# Java TFTP Implementation

Four Java console applications for comparing UDP file transfer with a simpler
TCP-based file-transfer protocol: a client and concurrent server for each transport.
Originally developed for Computer Networks coursework at the University of Sussex.

The UDP implementation uses TFTP-style RRQ/WRQ, DATA, ACK and ERROR packets in
**octet mode**, with 512-byte blocks, timeouts and bounded retransmission. The
TCP implementation is a **custom protocol**, not standard TFTP over TCP.

## What is included

```text
TFTP-UDP-Client/     Interactive client, packet codec and transfer state
TFTP-UDP-Server/     Listener, per-client handlers and protocol helpers
TFTP-TCP-Client/     Interactive client using framed TCP messages
TFTP-TCP-Server/     Concurrent server for the matching custom TCP protocol
tests/smoke.py      Dependency-free compile/socket regression checks
docs/               Project refresher and every cleanup change
```

The original four-project structure and default-package Java classes are retained.
Do not combine all sources into a single classpath: the server implementations
contain overlapping handler class names. There are no third-party Java dependencies.

## Build

Install **JDK 17 or newer** and put `java`/`javac` on PATH. From the repository root
in PowerShell:

```powershell
$modules = 'TFTP-UDP-Client','TFTP-UDP-Server','TFTP-TCP-Client','TFTP-TCP-Server'
foreach ($module in $modules) {
    New-Item -ItemType Directory -Force "$module/target/classes" | Out-Null
    $sources = @(Get-ChildItem "$module/src/main/java/*.java" | ForEach-Object FullName)
    javac --release 17 -d "$module/target/classes" @sources
}
New-Item -ItemType Directory -Force -Path runtime/server,runtime/client | Out-Null
```

On a POSIX shell, compile one module with:

```sh
mkdir -p TFTP-UDP-Server/target/classes
javac --release 17 -d TFTP-UDP-Server/target/classes TFTP-UDP-Server/src/main/java/*.java
```

Repeat for the other three module names. Maven project files are also retained;
the originally empty UDP-server POM has been repaired. Each can be imported
separately in an IDE. The cleanup checks used `javac --release 17`, not a Maven install.

## Run on your own machine

Use **separate working directories** for the server and client, so transfers cannot
accidentally target the same local file. Put only files you intend to share in
`runtime/server`. Create a small `hello.txt` of your own there to try downloading.

Server terminal, starting from the repository root:

```powershell
Set-Location runtime/server
java -cp ../../TFTP-UDP-Server/target/classes TFTPServer
```

Client terminal, also initially at the repository root:

```powershell
Set-Location runtime/client
java -cp ../../TFTP-UDP-Client/target/classes TFTPClient
```

Choose download/upload, keep the localhost/default port prompts, and enter the
**basename**, such as `hello.txt`. An upload file must already be in the client's
working directory. UDP request filenames must be ASCII; the custom TCP variant
uses UTF-8 names. Existing files are not silently overwritten. Remove or rename
your own test destination explicitly before repeating a transfer to the same name.

For TCP, use the matching programs in separate terminals:

```powershell
# From runtime/server:
java -cp ../../TFTP-TCP-Server/target/classes TFTPServerTCP
# From runtime/client:
java -cp ../../TFTP-TCP-Client/target/classes TFTPClientTCP
```

| Transport | Default port | Server arguments |
| --- | --- | --- |
| UDP | 1069 | `TFTPServer [port] [bind-address]` |
| TCP | 12345 | `TFTPServerTCP [port] [bind-address]` |

Both servers bind to **127.0.0.1 by default**. For a deliberate trusted-LAN demo,
provide an explicit interface address and use a dedicated directory/firewall rule.
The server can read and create files in its working directory; it has no login or
encryption. Do not expose it to the internet or run it in a directory containing secrets.

## Protocol behaviour

UDP uses the original four-byte DATA header: two-byte opcode and two-byte block
number, followed by up to 512 raw bytes. RRQ starts a download; WRQ receives ACK 0
before upload data. The server replies from a per-transfer UDP port, which the
client records after validating the first response. Subsequent traffic is tied
to that peer. A payload shorter than 512 ends a file; an exact multiple or empty
file needs a final zero-length DATA block.

Timeouts retransmit the outstanding packet, with five bounded attempts. Duplicate
initial requests do not spawn a second active writer for the same client endpoint.
Receivers briefly keep the final ACK available for one retransmission. A UDP
download can therefore pause for roughly six seconds before reporting completion.
This is a small coursework implementation, not exhaustive loss/reordering handling
or a complete RFC extension/option-negotiation implementation.

### Essential TCP framing correction

The original TCP code treated each `read()` as one message, but TCP does not preserve
message boundaries. Both corrected TCP peers now use this DATA frame:

```text
opcode (2 bytes) | block number (2) | payload length (2) | payload (0–512)
```

Integers use network byte order. `readFully()` reconstructs exactly the advertised
payload length. Existing ACK messages, request strings and error messages remain;
the matching peers now consistently exchange/validate ACKs. The same final-short/
zero-length rule marks completion. **Old TCP client/server binaries cannot be
mixed with these corrected peers.** UDP's on-wire packet format is unchanged.

## Tests

Python 3 is needed only for the included test runner:

```sh
python tests/smoke.py
# If the JDK is not on PATH:
python tests/smoke.py --java-bin "C:/path/to/jdk/bin"
```

It compiles all four components into a temporary directory and uses loopback
servers and generated binary fixtures. It checks empty files, 511/512/513-byte
boundaries, larger binary transfers, missing/existing files, traversal, malformed
requests, fragmented TCP writes, interrupted-upload cleanup and UDP retransmission.
It does not read or overwrite your project files. Allow about a minute or two
because timeout/final-ACK checks deliberately wait.

## Scope and publication

The project demonstrates sockets, packet/stream framing, transfer state, binary
I/O, retransmission and concurrent request handling. It does not demonstrate secure
file hosting, authentication, encrypted transport, congestion control or production
resource management. The existing cached thread pools remain unbounded; that is
another reason to keep the demo local/trusted.

Server paths are restricted to basenames in the working directory, including a
canonical-path check against symlink escapes. New files use exclusive creation;
handled failed transfers remove only the partial file they created. Force-killing
a process can still leave a partial file; review/delete your own test output before
retrying. Sustained loss and hostile-network hardening are separate future work.

IDE state, compiled outputs, submission PDF and book-like test payloads were excluded
from the publication copy.

