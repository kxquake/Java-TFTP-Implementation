"""Compile all four modules and test only isolated temporary directories/loopback sockets."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--java-bin', help='JDK bin directory, if java/javac are not on PATH')
args = parser.parse_args()
java = str(Path(args.java_bin) / 'java') if args.java_bin else shutil.which('java')
javac = str(Path(args.java_bin) / 'javac') if args.java_bin else shutil.which('javac')
if not java or not javac:
    parser.error('Install JDK 17+ or supply --java-bin')
root = Path(__file__).resolve().parents[1]
modules = ['TFTP-UDP-Client', 'TFTP-UDP-Server', 'TFTP-TCP-Client', 'TFTP-TCP-Server']

def request(opcode, name, mode='octet'):
    return struct.pack('!H', opcode) + name.encode() + b'\0' + mode.encode() + b'\0'

def exact(sock, length):
    result = bytearray()
    while len(result) < length:
        part = sock.recv(length - len(result))
        if not part:
            raise EOFError('Truncated TCP frame')
        result.extend(part)
    return bytes(result)

with tempfile.TemporaryDirectory(prefix='java-tftp-check-') as directory, ExitStack() as stack:
    fixture = Path(directory)
    for module in modules:
        output = fixture / module
        output.mkdir()
        sources = sorted((root / module / 'src/main/java').glob('*.java'))
        subprocess.run([javac, '--release', '17', '-d', str(output), *map(str, sources)], check=True)
    server_dir, client_dir = fixture / 'server', fixture / 'client'
    server_dir.mkdir()
    client_dir.mkdir()
    ports = {}
    processes = []
    for protocol in ('UDP', 'TCP'):
        with socket.socket(type=socket.SOCK_DGRAM if protocol == 'UDP' else socket.SOCK_STREAM) as probe:
            probe.bind(('127.0.0.1', 0))
            ports[protocol] = probe.getsockname()[1]
        log = stack.enter_context(tempfile.TemporaryFile())
        entry = 'TFTPServer' if protocol == 'UDP' else 'TFTPServerTCP'
        process = subprocess.Popen([java, '-cp', str(fixture / f'TFTP-{protocol}-Server'),
                                    entry, str(ports[protocol])], cwd=server_dir, stdout=log, stderr=log)
        processes.append(process)
    try:
        for _ in range(60):
            try:
                with socket.create_connection(('127.0.0.1', ports['TCP']), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError('TCP server did not start')

        def client(protocol, choice, name, port=None):
            port = port or ports[protocol]
            data = f'{choice}\n127.0.0.1\n{port}\n{name}\n' if protocol == 'UDP' else f'127.0.0.1\n{port}\n{choice}\n{name}\n3\n'
            entry = 'TFTPClient' if protocol == 'UDP' else 'TFTPClientTCP'
            return subprocess.run([java, '-cp', str(fixture / f'TFTP-{protocol}-Client'), entry],
                                  input=data, text=True, capture_output=True, cwd=client_dir, timeout=30)

        # All bytes are generated here: no copyrighted book or private fixture is needed.
        for protocol in ('UDP', 'TCP'):
            for size in (0, 1, 511, 512, 513, 1024, 4097):
                payload = bytes((index * 37) % 256 for index in range(size))
                upload = f'{protocol.lower()}-up-{size}.bin'
                (client_dir / upload).write_bytes(payload)
                result = client(protocol, 2, upload)
                assert result.returncode == 0, result.stderr
                assert (server_dir / upload).read_bytes() == payload, (protocol, size, result.stdout, result.stderr)
                download = f'{protocol.lower()}-down-{size}.bin'
                (server_dir / download).write_bytes(payload)
                result = client(protocol, 1, download)
                assert result.returncode == 0, result.stderr
                assert (client_dir / download).read_bytes() == payload, (protocol, size, result.stdout, result.stderr)
            result = client(protocol, 1, 'missing.bin')
            assert not (client_dir / 'missing.bin').exists()
            assert 'error' in (result.stdout + result.stderr).lower()
            existing = client_dir / f'{protocol.lower()}-down-1.bin'
            before = existing.read_bytes()
            client(protocol, 1, existing.name)
            assert existing.read_bytes() == before
            print(f'PASS: {protocol} binary upload/download sizes 0,1,511,512,513,1024,4097; missing file; no local overwrite.', flush=True)

        def udp_request(payload):
            with socket.socket(type=socket.SOCK_DGRAM) as sock:
                sock.settimeout(3)
                sock.sendto(payload, ('127.0.0.1', ports['UDP']))
                return sock.recvfrom(517)[0]

        for name in ('../outside.txt', '..\\outside.txt', '/outside.txt', 'C:outside.txt', '.'):
            assert udp_request(request(1, name))[:2] == b'\0\5'
            with socket.create_connection(('127.0.0.1', ports['TCP']), timeout=3) as sock:
                sock.sendall(request(1, name))
                assert exact(sock, 2) == b'\0\5'
        assert udp_request(b'\0')[:2] == b'\0\5'
        assert udp_request(request(1, 'some.bin', 'netascii'))[:2] == b'\0\5'
        assert udp_request(request(2, 'udp-up-1.bin'))[:4] == b'\0\5\0\6'
        print('PASS: server traversal, unsupported mode, truncated request and remote overwrite rejection.', flush=True)

        # A short malformed packet must not shrink the reusable UDP receive buffer.
        with socket.socket(type=socket.SOCK_DGRAM) as peer:
            peer.settimeout(5)
            peer.sendto(request(2, 'malformed-then-valid.bin'), ('127.0.0.1', ports['UDP']))
            _, addr = peer.recvfrom(517)
            peer.sendto(b'\0', addr)
            assert peer.recvfrom(517)[0] == struct.pack('!HH', 4, 0)
            payload = bytes(range(256)) * 2
            peer.sendto(struct.pack('!HH', 3, 1) + payload, addr)
            assert peer.recvfrom(517)[0] == struct.pack('!HH', 4, 1)
            peer.sendto(struct.pack('!HH', 3, 2), addr)
            assert peer.recvfrom(517)[0] == struct.pack('!HH', 4, 2)
        assert (server_dir / 'malformed-then-valid.bin').read_bytes() == payload
        print('PASS: malformed UDP DATA followed by a full valid block does not truncate data.', flush=True)

        # Fragment every TCP field into single-byte writes; readFully must reconstruct frames.
        payload = bytes(range(256)) + b'fragmented'
        with socket.create_connection(('127.0.0.1', ports['TCP']), timeout=3) as sock:
            sock.sendall(request(2, 'fragmented.bin'))
            assert exact(sock, 4) == struct.pack('!HH', 4, 0)
            for byte in struct.pack('!HHH', 3, 1, len(payload)) + payload:
                sock.sendall(bytes([byte]))
            assert exact(sock, 4) == struct.pack('!HH', 4, 1)
        assert (server_dir / 'fragmented.bin').read_bytes() == payload
        with socket.create_connection(('127.0.0.1', ports['TCP']), timeout=3) as sock:
            sock.sendall(request(2, 'interrupted.bin'))
            assert exact(sock, 4) == struct.pack('!HH', 4, 0)
            sock.sendall(struct.pack('!HHH', 3, 1, 512) + b'only-a-prefix')
        for _ in range(30):
            if not (server_dir / 'interrupted.bin').exists():
                break
            time.sleep(0.1)
        assert not (server_dir / 'interrupted.bin').exists()
        print('PASS: fragmented TCP stream and incomplete-upload cleanup.', flush=True)

        # Independent minimal UDP peer: deliberately lose the initial WRQ and DATA ACK.
        with socket.socket(type=socket.SOCK_DGRAM) as peer, ThreadPoolExecutor(max_workers=1) as pool:
            peer.bind(('127.0.0.1', 0))
            peer.settimeout(10)
            name = 'retry.bin'
            payload = b'retry-check'
            (client_dir / name).write_bytes(payload)
            future = pool.submit(client, 'UDP', 2, name, peer.getsockname()[1])
            first, addr = peer.recvfrom(517)
            second, same = peer.recvfrom(517)
            assert first == second == request(2, name) and same == addr
            peer.sendto(struct.pack('!HH', 4, 0), addr)
            first, _ = peer.recvfrom(517)
            second, _ = peer.recvfrom(517)
            assert first == second == struct.pack('!HH', 3, 1) + payload
            peer.sendto(struct.pack('!HH', 4, 1), addr)
            assert future.result(timeout=10).returncode == 0
        print('PASS: UDP client retransmits lost request and unacknowledged DATA.', flush=True)

        # Server retransmits DATA until ACK, and accepts a retransmitted final upload block.
        (server_dir / 'retry-download.bin').write_bytes(b'packet')
        with socket.socket(type=socket.SOCK_DGRAM) as peer:
            peer.settimeout(10)
            peer.sendto(request(1, 'retry-download.bin'), ('127.0.0.1', ports['UDP']))
            first, addr = peer.recvfrom(517)
            second, _ = peer.recvfrom(517)
            assert first == second == struct.pack('!HH', 3, 1) + b'packet'
            peer.sendto(struct.pack('!HH', 4, 1), addr)
        with socket.socket(type=socket.SOCK_DGRAM) as peer:
            peer.settimeout(5)
            peer.sendto(request(2, 'final-retry.bin'), ('127.0.0.1', ports['UDP']))
            ack, addr = peer.recvfrom(517)
            assert ack == struct.pack('!HH', 4, 0)
            block = struct.pack('!HH', 3, 1) + b'once-only'
            peer.sendto(block, addr)
            peer.recvfrom(517)  # Treat this ACK as lost.
            peer.sendto(block, addr)
            assert peer.recvfrom(517)[0] == struct.pack('!HH', 4, 1)
        assert (server_dir / 'final-retry.bin').read_bytes() == b'once-only'
        print('PASS: UDP server retransmission and final ACK retry without duplicate writes.', flush=True)

        with socket.socket(type=socket.SOCK_DGRAM) as peer:
            peer.settimeout(5)
            initial = request(2, 'initial-ack-retry.bin')
            peer.sendto(initial, ('127.0.0.1', ports['UDP']))
            first, addr = peer.recvfrom(517)
            assert first == struct.pack('!HH', 4, 0)
            peer.sendto(initial, ('127.0.0.1', ports['UDP']))
            retry, same = peer.recvfrom(517)
            assert retry == first and same == addr
            peer.sendto(struct.pack('!HH', 3, 1) + b'initial-retry', addr)
            assert peer.recvfrom(517)[0] == struct.pack('!HH', 4, 1)
        assert (server_dir / 'initial-ack-retry.bin').read_bytes() == b'initial-retry'
        print('PASS: duplicate initial UDP request reuses the active transfer, not a second writer.', flush=True)
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            process.wait(timeout=10)
