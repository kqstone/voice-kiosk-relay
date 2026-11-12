import unittest
from fastapi import HTTPException
from app.config import settings
from app.auth import extract_bearer_token, verify_push_token, verify_device_token
from app.main import app, health, push_board_skeleton


class TestHealthAndAuth(unittest.TestCase):
    def setUp(self) -> None:
        self.original_push_token = settings.push_token
        self.original_device_token = settings.device_token
        settings.push_token = "test-secret-push-token"
        settings.device_token = "test-secret-device-token"

    def tearDown(self) -> None:
        settings.push_token = self.original_push_token
        settings.device_token = self.original_device_token

    def test_health_endpoint_direct(self) -> None:
        res = health()
        self.assertEqual(res, {"ok": True})

    def test_extract_bearer_token_missing(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            extract_bearer_token(None)
        self.assertEqual(ctx.exception.status_code, 401)

    def test_extract_bearer_token_invalid_format(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            extract_bearer_token("Basic 123456")
        self.assertEqual(ctx.exception.status_code, 401)

        with self.assertRaises(HTTPException) as ctx:
            extract_bearer_token("Bearer")
        self.assertEqual(ctx.exception.status_code, 401)

    def test_verify_push_token_success(self) -> None:
        token = verify_push_token("Bearer test-secret-push-token")
        self.assertEqual(token, "test-secret-push-token")

    def test_verify_push_token_wrong_token(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            verify_push_token("Bearer wrong-token")
        self.assertEqual(ctx.exception.status_code, 403)

    def test_verify_device_token_success(self) -> None:
        token = verify_device_token("Bearer test-secret-device-token")
        self.assertEqual(token, "test-secret-device-token")

    def test_verify_device_token_wrong_token(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            verify_device_token("Bearer wrong-token")
        self.assertEqual(ctx.exception.status_code, 403)

    def test_api_client_if_available(self) -> None:
        try:
            from fastapi.testclient import TestClient
            client = TestClient(app)

            # Health check without auth
            resp = client.get("/health")
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.json(), {"ok": True})

            # Push endpoint without auth
            resp = client.post("/api/v1/push/test_board")
            self.assertEqual(resp.status_code, 401)

            # Push endpoint with wrong auth
            resp = client.post(
                "/api/v1/push/test_board",
                headers={"Authorization": "Bearer wrong-token"},
            )
            self.assertEqual(resp.status_code, 403)

            # Push endpoint with valid auth
            resp = client.post(
                "/api/v1/push/test_board",
                headers={"Authorization": "Bearer test-secret-push-token"},
            )
            self.assertEqual(resp.status_code, 200)
            self.assertTrue(resp.json().get("ok"))
        except ImportError:
            # httpx not installed, direct unit tests verified above
            pass


if __name__ == "__main__":
    unittest.main()
