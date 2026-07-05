#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
메타데이터 추출기 (Java <-> Python 연결용)

Java(Spring Boot) 측에서 ProcessBuilder 로 이 스크립트를 실행하고,
stdin 으로 JSON 요청을 전달하면 stdout 으로 JSON 결과를 반환한다.

요청(JSON):
    {"type": "mail_header" | "url", "value": "<원본 문자열>"}

응답(JSON):
    성공: {"success": true,  "type": "...", "metadata": {...}}
    실패: {"success": false, "type": "...", "error": "메시지"}

표준 라이브러리만 사용하므로 별도 pip 설치가 필요 없다.
"""
import sys
import json
import ssl
import email
from email import policy
from email.header import decode_header, make_header
from html.parser import HTMLParser
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

# ---------------------------------------------------------------------------
# 메일 헤더 추출
# ---------------------------------------------------------------------------

def _decode(value):
    """RFC 2047 인코딩된 헤더 값을 사람이 읽을 수 있는 문자열로 변환."""
    if value is None:
        return None
    try:
        return str(make_header(decode_header(value)))
    except Exception:
        return str(value)


def extract_mail_header(raw):
    """원본 메일 헤더(또는 전체 메일) 문자열에서 주요 메타데이터를 추출."""
    msg = email.message_from_string(raw, policy=policy.default)

    single_fields = [
        "From", "To", "Cc", "Bcc", "Reply-To", "Subject", "Date",
        "Message-ID", "In-Reply-To", "References", "Return-Path",
        "Content-Type", "MIME-Version", "X-Mailer", "User-Agent",
    ]

    headers = {}
    for name in single_fields:
        if name in msg:
            headers[name] = _decode(msg.get(name))

    # 메일 전달 경로(Received)는 여러 개일 수 있으므로 리스트로 수집
    received = [_decode(v) for v in msg.get_all("Received", [])]

    # 인증 관련 헤더
    auth = {}
    for name in ("Authentication-Results", "Received-SPF", "DKIM-Signature",
                 "ARC-Authentication-Results"):
        values = msg.get_all(name)
        if values:
            auth[name] = [_decode(v) for v in values]

    return {
        "headers": headers,
        "received": received,
        "hopCount": len(received),
        "authentication": auth,
    }


# ---------------------------------------------------------------------------
# URL 메타데이터 추출
# ---------------------------------------------------------------------------

class _MetaParser(HTMLParser):
    """<title>, <meta> (Open Graph / Twitter / description) 태그를 수집."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = None
        self._in_title = False
        self.metas = {}          # name/property -> content
        self.canonical = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "title":
            self._in_title = True
        elif tag == "meta":
            key = attrs.get("property") or attrs.get("name")
            content = attrs.get("content")
            if key and content is not None:
                self.metas[key.lower()] = content
        elif tag == "link" and attrs.get("rel", "").lower() == "canonical":
            self.canonical = attrs.get("href")

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title and self.title is None:
            text = data.strip()
            if text:
                self.title = text


def extract_url(url):
    """URL 을 가져와 HTML 메타데이터(제목/설명/Open Graph 등)를 추출."""
    if not (url.startswith("http://") or url.startswith("https://")):
        raise ValueError("http(s) URL 만 지원합니다: %s" % url)

    req = Request(url, headers={
        "User-Agent": "Mozilla/5.0 (compatible; getMetaData/1.0; +java-python)",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    })
    ctx = ssl.create_default_context()

    with urlopen(req, timeout=15, context=ctx) as resp:
        final_url = resp.geturl()
        status = getattr(resp, "status", None)
        content_type = resp.headers.get("Content-Type", "")
        charset = resp.headers.get_content_charset() or "utf-8"
        # HTML 만 파싱 (과도한 다운로드 방지: 최대 2MB)
        raw = resp.read(2 * 1024 * 1024)

    html = raw.decode(charset, errors="replace")
    parser = _MetaParser()
    parser.feed(html)

    metas = parser.metas
    open_graph = {k: v for k, v in metas.items() if k.startswith("og:")}
    twitter = {k: v for k, v in metas.items() if k.startswith("twitter:")}

    return {
        "requestedUrl": url,
        "finalUrl": final_url,
        "statusCode": status,
        "contentType": content_type,
        "charset": charset,
        "title": parser.title,
        "description": metas.get("description"),
        "canonical": parser.canonical,
        "openGraph": open_graph,
        "twitter": twitter,
    }


# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------

def main():
    try:
        raw_input_text = sys.stdin.read()
        request = json.loads(raw_input_text) if raw_input_text.strip() else {}
        req_type = request.get("type")
        value = request.get("value", "")

        if req_type == "mail_header":
            metadata = extract_mail_header(value)
        elif req_type == "url":
            metadata = extract_url(value)
        else:
            raise ValueError("알 수 없는 type: %r (mail_header 또는 url)" % req_type)

        result = {"success": True, "type": req_type, "metadata": metadata}
    except (HTTPError, URLError) as e:
        result = {"success": False, "type": request.get("type") if 'request' in dir() else None,
                  "error": "네트워크 오류: %s" % e}
    except Exception as e:  # noqa: BLE001 - 모든 오류를 JSON 으로 전달
        result = {"success": False, "error": "%s: %s" % (type(e).__name__, e)}

    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
