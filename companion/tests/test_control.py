from __future__ import annotations

import unittest

from supervisor_companion.actions import ControlAction
from supervisor_companion.config import ConfigurationError
from supervisor_companion.control import ControlError, ModControlClient

from http_fixture import RecordingEndpoint


class ModControlClientTests(unittest.TestCase):
    def test_sends_allowlisted_control_contract(self) -> None:
        with RecordingEndpoint(
            lambda _: (
                200,
                {
                    "accepted": True,
                    "message": "Paused.",
                    "state": "STUCK",
                },
            )
        ) as endpoint:
            client = ModControlClient(endpoint.base_url, token="shared")
            result = client.send(ControlAction.PAUSE, request_id="request-1")

        request = endpoint.requests[0]
        self.assertEqual(request["path"], "/v1/control")
        self.assertEqual(request["body"]["action"], "PAUSE")
        self.assertEqual(request["body"]["request_id"], "request-1")
        self.assertIn("sent_at", request["body"])
        self.assertEqual(
            request["headers"]["X-Supervisor-Token"], "shared"
        )
        self.assertTrue(result.accepted)
        self.assertEqual(result.mod_state, "STUCK")

    def test_empty_response_is_not_treated_as_acknowledged(self) -> None:
        with RecordingEndpoint(lambda _: (204, b"")) as endpoint:
            with self.assertRaisesRegex(ControlError, "explicitly acknowledge"):
                ModControlClient(endpoint.base_url).send(ControlAction.START)

    def test_missing_accepted_field_is_rejected(self) -> None:
        with RecordingEndpoint(
            lambda _: (200, {"message": "Maybe"})
        ) as endpoint:
            with self.assertRaisesRegex(ControlError, "explicitly acknowledge"):
                ModControlClient(endpoint.base_url).send(ControlAction.START)

    def test_rejected_control_raises(self) -> None:
        with RecordingEndpoint(
            lambda _: (200, {"accepted": False, "message": "Wrong state."})
        ) as endpoint:
            with self.assertRaisesRegex(ControlError, "Wrong state"):
                ModControlClient(endpoint.base_url).send(ControlAction.RESUME)

    def test_invalid_response_shape_raises(self) -> None:
        with RecordingEndpoint(lambda _: (200, ["not", "object"])) as endpoint:
            with self.assertRaisesRegex(ControlError, "not an object"):
                ModControlClient(endpoint.base_url).send(ControlAction.STOP)

    def test_invalid_accepted_type_raises(self) -> None:
        with RecordingEndpoint(
            lambda _: (200, {"accepted": "yes"})
        ) as endpoint:
            with self.assertRaisesRegex(ControlError, "accepted"):
                ModControlClient(endpoint.base_url).send(ControlAction.STOP)

    def test_http_error_does_not_expose_endpoint_body(self) -> None:
        with RecordingEndpoint(
            lambda _: (500, {"error": "internal detail"})
        ) as endpoint:
            with self.assertRaisesRegex(ControlError, "could not reach"):
                ModControlClient(endpoint.base_url).send(ControlAction.STOP)

    def test_malformed_chunked_response_is_a_control_delivery_error(self) -> None:
        for status in (200, 500):
            with self.subTest(status=status), RecordingEndpoint(
                lambda _, code=status: (
                    code, b"not-a-chunk\r\n", {"Transfer-Encoding": "chunked"}
                )
            ) as endpoint:
                with self.assertRaisesRegex(ControlError, "could not reach"):
                    ModControlClient(endpoint.base_url).send(ControlAction.PAUSE)

    def test_non_enum_action_is_rejected_before_network(self) -> None:
        client = ModControlClient("http://127.0.0.1:1")
        with self.assertRaises(TypeError):
            client.send("STOP")

    def test_direct_client_rejects_remote_mod_address(self) -> None:
        with self.assertRaises(ConfigurationError):
            ModControlClient("https://example.com")

    def test_redirect_is_not_followed(self) -> None:
        with RecordingEndpoint() as target, RecordingEndpoint(
            lambda _: (
                302,
                {"redirect": True},
                {"Location": f"{target.base_url}/v1/control"},
            )
        ) as redirector:
            client = ModControlClient(redirector.base_url, token="shared")
            with self.assertRaises(ControlError):
                client.send(ControlAction.PAUSE)
        self.assertEqual(target.requests, [])


if __name__ == "__main__":
    unittest.main()
