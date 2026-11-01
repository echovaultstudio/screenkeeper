package app.screenkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbAddressTest {
    @Test fun hostOnlyUsesDefaultPort() = assertEquals("192.168.1.50" to 5555, AdbClient.parseAddress(" 192.168.1.50 "))
    @Test fun hostAndPort() = assertEquals("10.0.0.7" to 5556, AdbClient.parseAddress("10.0.0.7:5556"))
    @Test fun badPort() = assertNull(AdbClient.parseAddress("10.0.0.7:99999"))
    @Test fun empty() = assertNull(AdbClient.parseAddress(""))
}
