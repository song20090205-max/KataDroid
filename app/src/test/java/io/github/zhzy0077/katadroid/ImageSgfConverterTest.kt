package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.sgf.ImageSgfConverter
import io.github.zhzy0077.katadroid.sgf.ImageStone
import io.github.zhzy0077.katadroid.sgf.SgfCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageSgfConverterTest {
    @Test fun numberedStonesBecomeOrderedMainLineAndUnnumberedBecomeSetup() {
        val sgf = ImageSgfConverter.convert(listOf(
            ImageStone(3, 3, 1, 1),
            ImageStone(15, 15, 2, 2),
            ImageStone(3, 15, 1, 3),
            ImageStone(9, 9, 1),
            ImageStone(10, 10, 2),
        ))
        val root = SgfCodec.parse(sgf).single()
        assertEquals(listOf("jj"), root.properties["AB"])
        assertEquals(listOf("kk"), root.properties["AW"])
        assertEquals("dd", root.children.single().properties["B"]?.single())
        assertEquals("pp", root.children.single().children.single().properties["W"]?.single())
        assertEquals("dp", root.children.single().children.single().children.single().properties["B"]?.single())
    }

    @Test fun rejectsMissingNumbersGapsDuplicateIntersectionsAndWrongColors() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageSgfConverter.convert(listOf(ImageStone(0, 0, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSgfConverter.convert(listOf(ImageStone(0, 0, 1, 1), ImageStone(1, 0, 2, 3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSgfConverter.convert(listOf(ImageStone(0, 0, 1, 1), ImageStone(0, 0, 2, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSgfConverter.convert(listOf(ImageStone(0, 0, 2, 1)))
        }
    }
}
