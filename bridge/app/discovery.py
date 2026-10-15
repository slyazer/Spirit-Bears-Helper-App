"""Zero-configuration LAN discovery (mDNS / DNS-SD).

Advertises the bridge as ``_macromonitor._tcp.local.`` so the Android app can
find it automatically on the local network instead of being given an address by
hand. Discovery is best-effort: if the optional ``zeroconf`` dependency is
missing, or the network blocks multicast, the bridge runs exactly as before and
users fall back to QR pairing or manual entry.

The async API is used deliberately: the bridge's startup runs inside an asyncio
event loop, and the synchronous ``Zeroconf`` helper would try to schedule work
on that same loop and deadlock (``EventLoopBlocked``). ``AsyncZeroconf`` awaits
registration properly.

Nothing here exposes the service beyond the LAN; mDNS is link-local by design.
"""

from __future__ import annotations

import logging
import socket

logger = logging.getLogger("macro_monitor.discovery")

SERVICE_TYPE = "_macromonitor._tcp.local."


class MdnsAdvertiser:
    def __init__(
        self,
        port: int,
        service_name: str = "Macro Monitor Bridge",
        version: str = "0.0.0",
        addresses: list[str] | None = None,
    ) -> None:
        self._port = port
        self._name = service_name
        self._version = version
        self._addresses = addresses or []
        self._azc = None
        self._info = None

    async def start(self) -> bool:
        try:
            from zeroconf import ServiceInfo  # type: ignore
            from zeroconf.asyncio import AsyncZeroconf  # type: ignore
        except Exception as exc:  # noqa: BLE001 - optional dependency
            logger.info("mDNS discovery disabled (zeroconf unavailable): %s", exc)
            return False

        if not self._addresses:
            try:
                self._addresses = [socket.gethostbyname(socket.gethostname())]
            except OSError:
                self._addresses = []

        packed = [socket.inet_aton(ip) for ip in self._addresses if _is_ipv4(ip)]
        if not packed:
            logger.info("mDNS discovery skipped: no IPv4 addresses to advertise")
            return False

        instance = f"{self._name}.{SERVICE_TYPE}"
        try:
            self._info = ServiceInfo(
                SERVICE_TYPE,
                instance,
                addresses=packed,
                port=self._port,
                properties={
                    "name": self._name,
                    "version": self._version,
                    "path": "/",
                },
                server=f"{socket.gethostname()}.local.",
            )
            self._azc = AsyncZeroconf()
            await self._azc.async_register_service(self._info)
            logger.info(
                "mDNS advertising %s on %s (port %d)",
                instance,
                ", ".join(self._addresses),
                self._port,
            )
            return True
        except Exception as exc:  # noqa: BLE001
            logger.warning("mDNS advertising failed: %r", exc)
            await self.stop()
            return False

    async def stop(self) -> None:
        try:
            if self._azc is not None and self._info is not None:
                await self._azc.async_unregister_service(self._info)
            if self._azc is not None:
                await self._azc.async_close()
        except Exception as exc:  # noqa: BLE001
            logger.debug("mDNS shutdown warning: %s", exc)
        finally:
            self._azc = None
            self._info = None


def _is_ipv4(value: str) -> bool:
    try:
        socket.inet_aton(value)
        return value.count(".") == 3
    except OSError:
        return False
