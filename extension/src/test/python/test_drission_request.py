import base64
import importlib.util
import types
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "main" / "resources" / "browser" / "drission_request.py"
SPEC = importlib.util.spec_from_file_location("passrs_drission_request", SCRIPT)
BRIDGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRIDGE)


class FakeResponse:
    def __init__(self, body, *, encoded=False, status=200):
        self.status = status
        self.statusText = "OK"
        self.headers = {"Content-Type": "application/octet-stream" if encoded else "text/plain"}
        self.raw_body = body
        self._is_base64_body = encoded
        self.url = "https://api.example.test/items"


class FakePacket:
    def __init__(self, response):
        self.response = response
        self.request = types.SimpleNamespace(
            method="POST",
            url="https://api.example.test/items",
            headers={},
        )
        self.url = self.request.url


class CorsBlockedResponse:
    status = None
    statusText = ""
    raw_body = None
    body = None
    url = "https://api.example.test/items"

    @property
    def headers(self):
        raise TypeError("CORS response is only available through extra info")


class FakeTab:
    def __init__(self):
        self.calls = []

    def run_cdp(self, method, **kwargs):
        self.calls.append((method, kwargs))


class DrissionRequestTest(unittest.TestCase):
    def request(self):
        return {
            "method": "POST",
            "url": "https://api.example.test/items",
            "headers": ["Content-Type: text/plain"],
            "body": b"payload",
        }

    def test_observed_packet_recovers_text_and_binary_bodies(self):
        text_packet = FakePacket(FakeResponse("response text"))
        self.assertEqual(BRIDGE.packet_response_body_bytes(text_packet), b"response text")

        raw = b"\x00\xffbinary"
        binary_packet = FakePacket(FakeResponse(base64.b64encode(raw).decode("ascii"), encoded=True))
        self.assertEqual(BRIDGE.packet_response_body_bytes(binary_packet), raw)

    def test_failed_post_fetch_is_not_replayed_with_xhr(self):
        original_fetch = BRIDGE.execute_fetch_request
        original_xhr = BRIDGE.execute_xhr_request
        xhr_calls = []
        try:
            BRIDGE.execute_fetch_request = lambda *_: (_ for _ in ()).throw(RuntimeError("fetch failed"))
            BRIDGE.execute_xhr_request = lambda *_: xhr_calls.append(True)
            with self.assertRaisesRegex(RuntimeError, "fetch failed"):
                BRIDGE.execute_script_request(None, self.request(), 1)
        finally:
            BRIDGE.execute_fetch_request = original_fetch
            BRIDGE.execute_xhr_request = original_xhr
        self.assertEqual(xhr_calls, [])

    def test_failed_page_fetch_returns_the_observed_response(self):
        packet = FakePacket(FakeResponse("listener body"))
        original_execute = BRIDGE.execute_script_request
        original_wait = BRIDGE.wait_for_observed_request_packet
        try:
            BRIDGE.execute_script_request = lambda *_: (_ for _ in ()).throw(RuntimeError("TypeError: Failed to fetch"))
            BRIDGE.wait_for_observed_request_packet = lambda *_args, **_kwargs: packet
            result = BRIDGE.execute_script_request_with_observation(None, self.request(), 1)
        finally:
            BRIDGE.execute_script_request = original_execute
            BRIDGE.wait_for_observed_request_packet = original_wait

        self.assertEqual(result["status"], 200)
        self.assertEqual(result["body"], b"listener body")
        self.assertTrue(result["_passrs_observed_result"])
        self.assertIn("X-PassRS-Fallback: browser-network-listener", result["headers"])

    def test_cors_failure_uses_response_extra_info(self):
        packet = FakePacket(CorsBlockedResponse())
        packet._responseExtraInfo = {
            "statusCode": 202,
            "headers": {"Content-Type": "text/plain", "X-Extra": "yes"},
        }
        result = BRIDGE.observed_result_from_packet(packet, self.request())
        self.assertEqual(result["status"], 202)
        self.assertEqual(result["body"], b"")
        self.assertIn("X-Extra: yes", result["headers"])
        self.assertIn("X-PassRS-Observed-Body-Unavailable: true", result["headers"])
        BRIDGE.finalize_result_with_metadata(None, self.request(), result, packet)

    def test_origin_is_used_when_referer_is_missing(self):
        request = self.request()
        request["headers"].append("Origin: https://app.example.test")
        self.assertEqual(BRIDGE.preferred_context_url(request), "https://app.example.test/")

    def test_network_configuration_clears_stale_blocks_and_bypasses_csp(self):
        tab = FakeTab()
        BRIDGE.configure_tab_network(tab, True)
        self.assertIn(("Network.setBlockedURLs", {"urls": []}), tab.calls)
        self.assertIn(("Page.setBypassCSP", {"enabled": True}), tab.calls)

    def test_packet_url_prefix_is_not_treated_as_the_same_request(self):
        request = self.request()
        packet = FakePacket(FakeResponse("body"))
        packet.url = request["url"] + "-archive"
        packet.request.url = packet.url
        self.assertFalse(BRIDGE.packet_matches_request(packet, request))

    def test_api_post_with_html_accept_is_not_navigation_when_fetch_headers_are_explicit(self):
        request = self.request()
        request["headers"].extend([
            "Accept: text/html,application/xhtml+xml",
            "Sec-Fetch-Mode: cors",
            "Sec-Fetch-Dest: empty",
        ])
        self.assertFalse(BRIDGE.is_navigation_post(request))

    def test_captured_challenge_response_must_match_original_post(self):
        request = self.request()
        matching = {
            "request_method": "POST",
            "request_url": request["url"],
        }
        unrelated = {
            "request_method": "GET",
            "request_url": request["url"],
        }
        self.assertTrue(BRIDGE.captured_result_matches_request(matching, request))
        self.assertFalse(BRIDGE.captured_result_matches_request(unrelated, request))
        unrelated["request_method"] = "POST"
        unrelated["request_url"] = request["url"] + "/other"
        self.assertFalse(BRIDGE.captured_result_matches_request(unrelated, request))

    def test_api_post_replays_after_challenge_instead_of_returning_current_page(self):
        request = self.request()
        challenge = {
            "status": 412,
            "headers": ["Content-Type: text/html"],
            "content_type": "text/html",
            "body": b"<html><script>document.cookie='token=ok';location.reload()</script></html>",
        }
        api_result = {
            "status": 200,
            "headers": ["Content-Type: application/json"],
            "content_type": "application/json",
            "body": b'{"ok":true}',
        }
        replacements = {
            "configure_tab_network": lambda *_: None,
            "set_request_cookies": lambda *_: None,
            "ensure_post_context": lambda *_: None,
            "restart_observed_request_capture": lambda *_: True,
            "load_challenge_page": lambda *_: None,
            "wait_for_post_challenge": lambda *_: None,
            "wait_for_matching_captured_browser_result": lambda *_: None,
            "wait_for_observed_non_challenge_result": lambda *_: None,
            "current_page_result": lambda *_: (_ for _ in ()).throw(
                AssertionError("API POST must not return the current HTML page")
            ),
        }
        originals = {name: getattr(BRIDGE, name) for name in replacements}
        responses = iter((challenge, api_result))
        original_execute = BRIDGE.execute_script_request_with_observation
        try:
            for name, replacement in replacements.items():
                setattr(BRIDGE, name, replacement)
            BRIDGE.execute_script_request_with_observation = lambda *_: next(responses)
            result = BRIDGE.execute_post_request(None, request, 1, False)
        finally:
            BRIDGE.execute_script_request_with_observation = original_execute
            for name, original in originals.items():
                setattr(BRIDGE, name, original)

        self.assertEqual(result["status"], 200)
        self.assertEqual(result["content_type"], "application/json")
        self.assertEqual(result["body"], b'{"ok":true}')

    def test_matching_auto_replayed_post_is_returned_without_duplicate_submission(self):
        request = self.request()
        challenge = {
            "status": 412,
            "headers": ["Content-Type: text/html"],
            "content_type": "text/html",
            "body": b"<html><script>document.cookie='token=ok'</script></html>",
        }
        captured = {
            "status": 200,
            "headers": ["Content-Type: application/json"],
            "content_type": "application/json",
            "body": b'{"auto":true}',
            "request_method": "POST",
            "request_url": request["url"],
        }
        replacements = {
            "configure_tab_network": lambda *_: None,
            "set_request_cookies": lambda *_: None,
            "ensure_post_context": lambda *_: None,
            "restart_observed_request_capture": lambda *_: True,
            "load_challenge_page": lambda *_: None,
            "wait_for_post_challenge": lambda *_: None,
            "wait_for_matching_captured_browser_result": lambda *_: captured,
            "wait_for_observed_non_challenge_result": lambda *_: (_ for _ in ()).throw(
                AssertionError("matching captured POST should be returned first")
            ),
        }
        originals = {name: getattr(BRIDGE, name) for name in replacements}
        calls = []
        original_execute = BRIDGE.execute_script_request_with_observation
        try:
            for name, replacement in replacements.items():
                setattr(BRIDGE, name, replacement)
            BRIDGE.execute_script_request_with_observation = lambda *_: calls.append(True) or challenge
            result = BRIDGE.execute_post_request(None, request, 1, False)
        finally:
            BRIDGE.execute_script_request_with_observation = original_execute
            for name, original in originals.items():
                setattr(BRIDGE, name, original)

        self.assertEqual(len(calls), 1)
        self.assertEqual(result["body"], b'{"auto":true}')


if __name__ == "__main__":
    unittest.main()
