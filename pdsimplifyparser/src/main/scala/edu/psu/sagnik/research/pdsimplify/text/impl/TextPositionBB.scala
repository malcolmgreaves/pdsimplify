package edu.psu.sagnik.research.pdsimplify.text.impl


import java.awt.geom.{AffineTransform, GeneralPath, Rectangle2D}

import edu.psu.sagnik.research.pdsimplify.model.Rectangle
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.{PDCIDFontType2, PDSimpleFont, PDType0Font, _}
import org.apache.pdfbox.text.TextPosition

/**
  * Created by schoudhury on 6/29/16.
  */
object TextPositionBB {

  def pageHeightAdjust(r:Rectangle,page:PDPage)=Rectangle(
    r.x1,
    page.getBBox.getHeight-r.y1,
    r.x2,
    page.getBBox.getHeight-r.y2
  )

  def approximate(tP:TextPosition,page:PDPage)=pageHeightAdjust(Rectangle(
    tP.getXDirAdj, // text can be rotated, which will change the x,y coordinates and bounding boxes
    tP.getYDirAdj - tP.getHeightDir,
    tP.getXDirAdj+tP.getWidthDirAdj,
    tP.getYDirAdj//(tP.getYDirAdj - tP.getHeightDir)+tP.getHeightDir
  ),page
  )

  def pageBasedAffineTransforms(pdPage:PDPage):(AffineTransform,AffineTransform)={
    val flipAT = new AffineTransform
    flipAT.translate(0, pdPage.getBBox.getHeight)
    flipAT.scale(1, -1)

    // page may be rotated
    val rotateAT = new AffineTransform
    val rotation: Int = pdPage.getRotation
    val mediaBox: PDRectangle = pdPage.getMediaBox
    rotation match {
      case 90 =>
        rotateAT.translate(mediaBox.getHeight, 0)
      case 270 =>
        rotateAT.translate(0, mediaBox.getWidth)
      case 180 =>
        rotateAT.translate(mediaBox.getWidth, mediaBox.getHeight)
      case _ =>
    }
    rotateAT.rotate(Math.toRadians(rotation))
    (flipAT,rotateAT)
  }

  //see method writeString @org.apache.pdfbox.examples.util.DrawPrintTextLocations
  //TODO: check correctness
  def fontBased(tP:TextPosition,pdPage:PDPage):Rectangle={
    val font = tP.getFont
    val bbox = font.getBoundingBox

    val (flipAT,rotateAT)=pageBasedAffineTransforms(pdPage)

    // advance width, bbox height (glyph space)
    val xAdvance = font.getWidth(tP.getCharacterCodes()(0))
    val rect = new Rectangle2D.Float(0, bbox.getLowerLeftY, xAdvance, bbox.getHeight)

    // glyph space -> user space
    // note: text.getTextMatrix() is *not* the Text Matrix, it's the Text Rendering Matrix
    val at = tP.getTextMatrix.createAffineTransform
    if (font.isInstanceOf[PDType3Font]) {
      at.concatenate(font.getFontMatrix.createAffineTransform)
    }
    else {
      at.scale(1 / 1000f, 1 / 1000f)
    }

    val s = rotateAT.createTransformedShape(flipAT.createTransformedShape(at.createTransformedShape(rect)))
    pageHeightAdjust(
    Rectangle(
      s.getBounds2D.getMinX.toFloat,
      s.getBounds2D.getMinY.toFloat,
      s.getBounds2D.getMaxX.toFloat,
      s.getBounds2D.getMaxY.toFloat
    ),pdPage)
  }



  def glyphBased(t:TextPosition,page:PDPage):Option[Rectangle]={

    val (flipAT,rotateAT)=pageBasedAffineTransforms(page)
    val at = t.getTextMatrix.createAffineTransform
    at.concatenate(t.getFont.getFontMatrix.createAffineTransform)
    val font=t.getFont
    val paths=
      font match{
        case font:PDType3Font =>
          val charProcs=t.getCharacterCodes.map(font.getCharProc)
          charProcs.filter(_!=null).map(_.getGlyphBBox).filter(_!=null).map(_.toGeneralPath)

        case font:PDVectorFont =>
          font match{
            case font:PDTrueTypeFont =>
              val ttFont = font.asInstanceOf[PDTrueTypeFont]
              val unitsPerEm = ttFont.getTrueTypeFont.getHeader.getUnitsPerEm
              at.scale(1000d / unitsPerEm, 1000d / unitsPerEm)

            case font:PDType0Font =>
              font.getDescendantFont match{
                case f : PDCIDFontType2 =>
                  val unitsPerEm = f.getTrueTypeFont.getHeader.getUnitsPerEm
                  at.scale(1000d / unitsPerEm, 1000d / unitsPerEm)

                case _ =>

              }
          }
          t.getCharacterCodes.map(font.getPath)

        case font:PDSimpleFont => t.getCharacterCodes.map(font.getEncoding.getName(_)).map(font.getPath)

        case _ => Array.empty[GeneralPath]
      }

    val beforePageTransformation=paths.map(x => at.createTransformedShape(x.getBounds2D))
    val afterPageTransformation=beforePageTransformation.map(x=>rotateAT.createTransformedShape(flipAT.createTransformedShape(x)))

    if (afterPageTransformation.isEmpty)
      None
    else
      Some(
        pageHeightAdjust(
        Rectangle(
          afterPageTransformation.map(_.getBounds.x).min,
          afterPageTransformation.map(_.getBounds.y).min,
          afterPageTransformation.map(a => a.getBounds.x + a.getBounds.width).max,
          afterPageTransformation.map(a => a.getBounds.y + a.getBounds.height).max
        ),page)
      )

  }

}
