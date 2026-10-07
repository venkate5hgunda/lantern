"""Create personal Lantern TLS identities without distributing the CA signing key."""

from __future__ import annotations

import argparse
import getpass
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile


def _openssl(arguments: list[str], password: str | None = None) -> str:
    executable = shutil.which("openssl")
    if executable is None:
        raise RuntimeError("OpenSSL is required to provision camera certificates.")
    result = subprocess.run(
        [executable, *arguments],
        input=None if password is None else password + "\n",
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode:
        detail = result.stderr.strip()[-1000:]
        raise RuntimeError(f"OpenSSL failed during {arguments[0]}: {detail}")
    return result.stdout


def validate_password(password: str) -> None:
    if len(password) < 12 or "\n" in password or "\r" in password or "\0" in password:
        raise ValueError("Use a password of at least 12 characters without newline or NUL characters.")


def _new_output(output: Path) -> Path:
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError(f"Refusing to overwrite an existing path: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    return output


def _san(value: str) -> str:
    try:
        return "IP:" + str(ipaddress.ip_address(value))
    except ValueError:
        labels = value.rstrip(".").split(".")
        if (
            len(value) > 253
            or not labels
            or any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in labels)
        ):
            raise ValueError(f"Invalid certificate hostname/IP: {value!r}")
        return "DNS:" + ".".join(labels).lower()


def _publish(staging: Path, output: Path) -> None:
    output.mkdir(mode=0o700)
    published: list[Path] = []
    try:
        for source in staging.iterdir():
            destination = output / source.name
            os.link(source, destination)
            published.append(destination)
    except OSError:
        for destination in published:
            destination.unlink()
        output.rmdir()
        raise


def init_ca(output: Path, password: str) -> Path:
    validate_password(password)
    output = _new_output(output)
    with tempfile.TemporaryDirectory(prefix=".lantern-ca-", dir=output.parent) as temporary:
        staging = Path(temporary) / "identity"
        staging.mkdir(mode=0o700)
        key = staging / "ca-key.pem"
        certificate = staging / "ca-cert.pem"
        _openssl([
            "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
            "-aes-256-cbc", "-pass", "stdin", "-out", str(key),
        ], password)
        key.chmod(0o600)
        _openssl([
            "req", "-new", "-x509", "-sha256", "-days", "3650",
            "-key", str(key), "-passin", "stdin", "-out", str(certificate),
            "-subj", "/CN=Lantern Household CA",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
        ], password)
        certificate.chmod(0o644)
        (staging / "README.txt").write_text(
            "Keep ca-key.pem and its password offline. Never import the CA signing key into a phone.\n"
            "Install only ca-cert.pem on viewers and explicitly enable TLS trust.\n"
            "This private CA can authenticate devices; protect its signing authority.\n",
            encoding="utf-8",
        )
        _publish(staging, output)
    return output


def create_peer(
    ca: Path, output: Path, name: str, hosts: list[str],
    ca_password: str, export_password: str,
) -> Path:
    validate_password(ca_password)
    validate_password(export_password)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 -]{0,63}", name):
        raise ValueError("Peer name must contain 1 to 64 ASCII letters, digits, spaces or hyphens.")
    if not hosts or len(hosts) > 12:
        raise ValueError("Provide between 1 and 12 exact DNS names/IPs used to reach this phone.")
    sans = list(dict.fromkeys(_san(host) for host in hosts))
    key = ca / "ca-key.pem"
    certificate = ca / "ca-cert.pem"
    if not key.is_file() or not certificate.is_file():
        raise ValueError("CA directory must contain ca-key.pem and ca-cert.pem.")
    output = _new_output(output)
    with tempfile.TemporaryDirectory(prefix=".lantern-peer-", dir=output.parent) as temporary:
        working = Path(temporary)
        staging = working / "identity"
        staging.mkdir(mode=0o700)
        leaf_key = working / "peer-key.pem"
        csr = working / "peer.csr"
        extensions = working / "extensions.cnf"
        leaf_certificate = staging / "device-cert.pem"
        identity = staging / "device.p12"
        _openssl([
            "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", str(leaf_key),
        ])
        leaf_key.chmod(0o600)
        _openssl(["req", "-new", "-sha256", "-key", str(leaf_key),
                  "-subj", f"/CN={name}", "-out", str(csr)])
        extensions.write_text(
            "[peer]\n"
            "basicConstraints=critical,CA:FALSE\n"
            "keyUsage=critical,digitalSignature,keyEncipherment\n"
            "extendedKeyUsage=serverAuth\n"
            "subjectAltName=" + ",".join(sans) + "\n",
            encoding="ascii",
        )
        _openssl([
            "x509", "-req", "-sha256", "-days", "365", "-in", str(csr),
            "-CA", str(certificate), "-CAkey", str(key), "-passin", "stdin",
            "-set_serial", "0x" + secrets.token_hex(16),
            "-extfile", str(extensions), "-extensions", "peer",
            "-out", str(leaf_certificate),
        ], ca_password)
        _openssl([
            "pkcs12", "-export", "-name", "lantern", "-inkey", str(leaf_key),
            "-in", str(leaf_certificate), "-certfile", str(certificate),
            "-iter", "100000", "-passout", "stdin", "-out", str(identity),
        ], export_password)
        identity.chmod(0o600)
        leaf_certificate.chmod(0o644)
        _openssl(["verify", "-CAfile", str(certificate), str(leaf_certificate)])
        (staging / "identity.json").write_text(
            json.dumps({"name": name, "subjectAlternativeNames": sans, "validDays": 365}, indent=2) + "\n",
            encoding="utf-8",
        )
        (staging / "README.txt").write_text(
            "Import device.p12 into this camera only, using its export password.\n"
            "Do not import ca-key.pem. Install the separate CA certificate on viewers.\n"
            "The identity expires in one year; issue a replacement before expiry.\n"
            "A new address/name not listed in the certificate requires reissuance.\n"
            "Remove temporary Downloads copies from the phone after a successful import.\n",
            encoding="utf-8",
        )
        _publish(staging, output)
    return output


def _password(prompt: str, confirm: bool = False) -> str:
    password = getpass.getpass(prompt)
    validate_password(password)
    if confirm:
        repeated = getpass.getpass("Confirm password: ")
        if not hmac.compare_digest(password.encode("utf-8"), repeated.encode("utf-8")):
            raise ValueError("Passwords do not match.")
    return password


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    initialize = commands.add_parser("init-ca", help="Create a new offline household CA.")
    initialize.add_argument("--out", type=Path, required=True)
    peer = commands.add_parser("create-peer", help="Issue a camera's individual PKCS#12 identity.")
    peer.add_argument("--ca", type=Path, required=True)
    peer.add_argument("--out", type=Path, required=True)
    peer.add_argument("--name", required=True)
    peer.add_argument("--san", action="append", required=True, help="Exact LAN/Meshnet DNS name or IP; repeat as needed.")
    arguments = parser.parse_args()
    try:
        if arguments.command == "init-ca":
            result = init_ca(arguments.out, _password("New offline CA password: ", confirm=True))
        else:
            result = create_peer(
                arguments.ca, arguments.out, arguments.name, arguments.san,
                _password("Offline CA password: "),
                _password("New camera identity password: ", confirm=True),
            )
    except (ValueError, RuntimeError, OSError) as error:
        parser.exit(1, f"Provisioning failed: {error}\n")
    print(f"Created {result}. Read its README.txt before importing the identity.")


if __name__ == "__main__":
    main()
