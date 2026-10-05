package com.example.ftpget

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PostPppProductsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun product(name: String, content: String = "product"): java.io.File =
        temporary.newFile(name).apply { writeText(content) }

    @Test fun rinexObservationSelectsMatchingProductsAndVmfPair() {
        val obs = product("GEOL00CHN_R_20262701300_00U_01S_MO.rnx",
            "  2026    09    27    13    00    00.0000000     GPS".padEnd(60) +
                "TIME OF FIRST OBS\n" + "".padEnd(60) + "END OF HEADER\n" +
                "> 2026 09 27 13 00 00.0000000  0  0\n")
        product("BRDC00IGS_R_20262700000_01D_MN.rnx")
        product("WUM0MGXNRT_20262691200_02D_05M_ORB.SP3")
        product("WUM0MGXNRT_20262691200_02D_05M_CLK.CLK")
        product("WUM0MGXNRT_20262700000_01D_01D_OSB.BIA")
        product("COD0OPSP0D_20262700000_01D_01H_GIM.INX")
        val vmf = "! Epoch: 2026 09 27 %s 00  0.0\n" +
            "-87.5 352.5 0.00116695 0.00052151 1.5767 0.0051\n".repeat(2_592)
        product("VMF3_20260927.H12", vmf.format("12"))
        product("VMF3_20260927.H18", vmf.format("18"))
        product("orography_ell_5x5", "19.36\n".repeat(4_000))
        product("igs20.atx", "     1.4            M      ANTEX VERSION / SYST\n" +
            "ANTEX\n".repeat(200_000))
        val result = PostPppProducts.resolve(temporary.root)
        assertEquals(obs.name, result.obs.name)
        assertEquals("VMF3_20260927.H12", result.vmfLeft.name)
        assertEquals("VMF3_20260927.H18", result.vmfRight.name)
        assertEquals(12, result.nativeArgs(temporary.newFile("solution.pos")).size)
    }
}
