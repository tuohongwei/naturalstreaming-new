import os
import pathlib
import re
import shutil
import subprocess
import sys

BASE = pathlib.Path("/srv/naturalstreaming")
CONFIG = pathlib.Path("/etc/naturalstreaming")
NGINX = pathlib.Path("/etc/nginx/conf.d/naturalstreaming.conf")
UNIT_DIR = pathlib.Path("/etc/systemd/system/naturalstreaming-admin.service.d")
UNITS = ("naturalstreaming-admin.service", "naturalstreaming-api.service")


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def update_nginx(text):
    old_upload = (
        " location = /admin/assets/import { client_max_body_size 257m; "
        "proxy_pass http://127.0.0.1:8191; proxy_read_timeout 300s; access_log off; }\n"
    )
    upload = old_upload.replace(
        "proxy_read_timeout 300s;", "proxy_http_version 1.1; proxy_request_buffering off; proxy_read_timeout 300s;"
    )
    text = text.replace(old_upload, upload)
    login = (
        " location = /admin/session { limit_req zone=ns_login burst=5 nodelay; "
        "limit_req_status 429; proxy_pass http://127.0.0.1:8191; access_log off; }\n"
    )
    media = (
        " location ^~ /v1/media/ { proxy_pass http://127.0.0.1:8191; "
        "proxy_read_timeout 180s; proxy_buffering off; access_log off; }\n"
    )
    rules = {
        "admin.naturalstreaming.net": upload + login,
        "admin-api.naturalstreaming.net": login,
        "media.naturalstreaming.net": media,
    }
    for host, locations in rules.items():
        marker = f"server {{ listen 443 ssl; server_name {host};\n"
        if text.count(marker) != 1:
            raise RuntimeError(f"cannot locate HTTPS server {host}")
        if text.count(marker + locations) == 0:
            text = text.replace(marker, marker + locations, 1)
    return text


def deploy(release):
    if not re.fullmatch(r"phase1-[0-9]+", release):
        raise ValueError("bad release name")
    path = BASE / "releases" / release
    jar = path / "naturalstreaming-admin.jar"
    if path.resolve() != path or not jar.is_file():
        raise RuntimeError("release payload missing")
    if not (CONFIG / "phase1-test.env").is_file():
        raise RuntimeError("test account bootstrap missing")
    current = BASE / "current"
    previous = os.readlink(current) if current.is_symlink() else None
    if not previous and current.exists():
        raise RuntimeError("current must be a symlink")

    run("mysql", "-e", "CREATE DATABASE IF NOT EXISTS naturalstreaming CHARACTER SET utf8mb4")
    env = os.environ.copy()
    for line in (CONFIG / "migrate.env").read_text().splitlines():
        key, value = line.split("=", 1)
        if key in {"DB_URL", "DB_USERNAME", "DB_PASSWORD"}:
            env[key] = value
    run("/opt/java/jdk-26/bin/java", "-jar", str(jar),
        "--spring.main.web-application-type=none", "--ns.role=migrate", "--spring.flyway.enabled=true", cwd=BASE, env=env)

    run("mysql", "-e",
        "GRANT SELECT,UPDATE ON naturalstreaming.ns_account TO 'ns_api'@'localhost';"
        "GRANT SELECT,INSERT,UPDATE ON naturalstreaming.ns_session TO 'ns_api'@'localhost';"
        "GRANT SELECT ON naturalstreaming.ns_theme TO 'ns_api'@'localhost';"
        "GRANT SELECT ON naturalstreaming.ns_preset TO 'ns_api'@'localhost';"
        "GRANT SELECT ON naturalstreaming.ns_preset_revision TO 'ns_api'@'localhost';"
        "GRANT SELECT ON naturalstreaming.ns_preset_asset TO 'ns_api'@'localhost';"
        "GRANT SELECT ON naturalstreaming.ns_asset TO 'ns_api'@'localhost'")
    UNIT_DIR.mkdir(exist_ok=True)
    dropin = UNIT_DIR / "phase1.conf"
    old_dropin = dropin.read_bytes() if dropin.is_file() else None
    old_nginx = NGINX.read_text()
    nginx_backup = BASE / "backups" / f"nginx-before-{release}.conf"
    shutil.copy2(NGINX, nginx_backup)
    try:
        dropin.write_text(
            "[Service]\nStateDirectory=naturalstreaming-bank\n"
            "StateDirectoryMode=0700\nEnvironmentFile=/etc/naturalstreaming/phase1-test.env\n"
        )
        NGINX.write_text(update_nginx(old_nginx))
        run("nginx", "-t")
        pending = BASE / f"current.{release}"
        pending.symlink_to(path.relative_to(BASE))
        pending.replace(current)
        run("systemctl", "daemon-reload")
        run("systemctl", "restart", *UNITS)
        for port in (8190, 8191):
            run("curl", "--silent", "--show-error", "--fail", "--retry", "20", "--retry-delay", "1",
                "--retry-connrefused", "--max-time", "5", f"http://127.0.0.1:{port}/healthz")
        run("systemctl", "reload", "nginx")
        run("systemctl", "enable", *UNITS)
    except BaseException:
        subprocess.run(["systemctl", "stop", *UNITS], check=False)
        NGINX.write_text(old_nginx)
        if dropin.exists():
            if old_dropin is None:
                dropin.unlink()
            else:
                dropin.write_bytes(old_dropin)
        if current.is_symlink() and os.readlink(current) == str(path.relative_to(BASE)):
            current.unlink()
            if previous:
                current.symlink_to(previous)
        subprocess.run(["systemctl", "daemon-reload"], check=False)
        subprocess.run(["systemctl", "reload", "nginx"], check=False)
        if previous:
            subprocess.run(["systemctl", "start", *UNITS], check=False)
        raise


if __name__ == "__main__":
    deploy(sys.argv[1])
