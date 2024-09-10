/*
 * Modern UI.
 * Copyright (C) 2019-2024 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.modernui.mc.text;

import icyllis.arc3d.engine.Engine;
import icyllis.arc3d.engine.ImmediateContext;
import icyllis.arc3d.opengl.GLDevice;
import icyllis.arc3d.opengl.GLTexture;
import icyllis.modernui.ModernUI;
import icyllis.modernui.annotation.*;
import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import icyllis.modernui.graphics.text.Font;
import icyllis.modernui.graphics.text.*;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;

import static icyllis.modernui.ModernUI.LOGGER;

/**
 * Manages all glyphs, font atlases, measures glyph metrics and draw them of
 * different sizes and styles, and upload them to generated OpenGL textures.
 *
 * @see GLFontAtlas
 * @see FontCollection
 * @see icyllis.arc3d.granite.GlyphAtlasManager
 */
public class GlyphManager {

    public static final Marker MARKER = MarkerManager.getMarker("Glyph");

    /**
     * The width in pixels of a transparent border between individual glyphs in the atlas.
     * This border keeps neighboring glyphs from "bleeding through" when mipmap used.
     * <p>
     * Additional notes: two pixels because we may use SDF to stroke.
     */
    public static final int GLYPH_BORDER = 2;

    /**
     * We only allow glyphs with a maximum of 256x256 pixels to be uploaded to the atlas.
     * This value must be less than {@link GLFontAtlas#CHUNK_SIZE}.
     */
    public static final int IMAGE_SIZE = 256;

    /**
     * Transparent (alpha zero) black background color for use with BufferedImage.clearRect().
     */
    private static final Color BG_COLOR = new Color(0, 0, 0, 0);

    /**
     * Config values.
     * Bitmap-like fonts, with anti aliasing and high precision OFF.
     * This may require additional reviews on pixel alignment.
     */
    public static volatile boolean sAntiAliasing = true;
    public static volatile boolean sFractionalMetrics = true;

    /**
     * Emoji font design.
     */
    public static final int EMOJI_SIZE = 72;
    public static final int EMOJI_ASCENT = 56;
    public static final int EMOJI_SPACING = 4;
    public static final int EMOJI_BASE = 64;

    /**
     * The global instance.
     */
    private static volatile GlyphManager sInstance;

    private GLFontAtlas mFontAtlas;
    private GLFontAtlas mEmojiAtlas;
    private GLFontAtlas mBitmapAtlas;
    private GLDevice mDevice;

    /**
     * Font (with size and style) to int key.
     */
    private final Object2IntOpenHashMap<java.awt.Font> mFontTable = new Object2IntOpenHashMap<>();

    private final ToIntFunction<java.awt.Font> mFontTableMapper = f -> mFontTable.size() + 1;

    private final Object2IntOpenHashMap<EmojiFont> mEmojiFontTable = new Object2IntOpenHashMap<>();

    private final ToIntFunction<EmojiFont> mEmojiFontTableMapper = f -> mEmojiFontTable.size() + 1;

    private final Object2IntOpenHashMap<BitmapFont> mBitmapFontTable = new Object2IntOpenHashMap<>();

    private final ToIntFunction<BitmapFont> mBitmapFontTableMapper = f -> mBitmapFontTable.size() + 1;

    /**
     * Draw a single glyph onto this image and then loaded from here into an OpenGL texture.
     */
    private BufferedImage mImage;

    /**
     * The Graphics2D associated with glyph image and used for bit blit.
     */
    private Graphics2D mGraphics;

    /**
     * Intermediate data array for use with image.
     */
    private int[] mImageData;

    /**
     * A direct buffer used for loading the pre-rendered glyph images into OpenGL textures.
     */
    private ByteBuffer mImageBuffer;

    //private ByteBuffer mEmojiBuffer;

    private final CopyOnWriteArrayList<Consumer<AtlasInvalidationInfo>> mAtlasInvalidationCallbacks
            = new CopyOnWriteArrayList<>();

    /**
     * Called when atlas resize or evict entries
     *
     * @param maskFormat type of atlas, {@link Engine#MASK_FORMAT_A8}
     * @param resize     true=texture resize, false=evict
     */
    public record AtlasInvalidationInfo(int maskFormat, boolean resize) {
    }

    private record FontStrikeDesc(Font font, int resLevel) {
    }

    /**
     * For fast digit replacement and obfuscated char rendering.
     * Map from 'derived font' to 'ASCII 33(!) to 126(~) characters with their standard advances and relative advances'.
     */
    private final Map<FontStrikeDesc, FastCharSet> mFastCharMap = new HashMap<>();
    // it's necessary to cache the lambda
    private final Function<FontStrikeDesc, FastCharSet> mCacheFastChars = this::cacheFastChars;

    private GlyphManager() {
        // init
        reload();
    }

    @NonNull
    public static GlyphManager getInstance() {
        if (sInstance == null) {
            synchronized (GlyphManager.class) {
                if (sInstance == null) {
                    sInstance = new GlyphManager();
                }
            }
        }
        return sInstance;
    }

    @RenderThread
    public void closeAtlases() {
        if (mFontAtlas != null) {
            mFontAtlas.close();
        }
        if (mEmojiAtlas != null) {
            mEmojiAtlas.close();
        }
        if (mBitmapAtlas != null) {
            mBitmapAtlas.close();
        }
        mFontAtlas = null;
        mEmojiAtlas = null;
        mBitmapAtlas = null;
    }

    /**
     * Reload the glyph manager, clear all created textures.
     */
    @RenderThread
    public void reload() {
        closeAtlases();
        mFontTable.clear();
        mFontTable.trim();
        mEmojiFontTable.clear();
        mEmojiFontTable.trim();
        mBitmapFontTable.clear();
        mBitmapFontTable.trim();
        clearFastCharMap();
        allocateImage();
    }

    public void clearFastCharMap() {
        mFastCharMap.clear();
    }

    /**
     * Given a font, perform full text layout/shaping and create a new GlyphVector for a text.
     *
     * @param text  the U16 text to layout
     * @param start the offset into text at which to start the layout
     * @param limit the (offset + length) at which to stop performing the layout
     * @param isRtl whether the text should layout right-to-left
     * @return the newly laid-out GlyphVector
     */
    @NonNull
    public GlyphVector layoutGlyphVector(@NonNull java.awt.Font awtFont, @NonNull char[] text,
                                         int start, int limit, boolean isRtl) {
        return awtFont.layoutGlyphVector(mGraphics.getFontRenderContext(), text, start, limit,
                isRtl ? java.awt.Font.LAYOUT_RIGHT_TO_LEFT : java.awt.Font.LAYOUT_LEFT_TO_RIGHT);
    }

    /**
     * Create glyph vector without text shaping, which means by mapping
     * characters to glyphs one-to-one.
     *
     * @param text the U16 text to layout
     * @return the newly created GlyphVector
     */
    @NonNull
    public GlyphVector createGlyphVector(@NonNull java.awt.Font awtFont, @NonNull char[] text) {
        return awtFont.createGlyphVector(mGraphics.getFontRenderContext(), text);
    }

    /**
     * Compute a glyph key used to retrieve GPU baked glyph, the key is valid
     * until next {@link #reload()}.
     *
     * @param glyphCode the font specific glyph code
     * @return a key
     */
    private long computeGlyphKey(@NonNull java.awt.Font awtFont, int glyphCode) {
        long fontKey = mFontTable.computeIfAbsent(awtFont, mFontTableMapper);
        return (fontKey << 32) | glyphCode;
    }

    private long computeEmojiKey(@NonNull EmojiFont font, int glyphId) {
        long fontKey = mEmojiFontTable.computeIfAbsent(font, mEmojiFontTableMapper);
        return (fontKey << 32) | glyphId;
    }

    private long computeBitmapGlyphKey(@NonNull BitmapFont font, int glyphId) {
        long fontKey = mBitmapFontTable.computeIfAbsent(font, mBitmapFontTableMapper);
        return (fontKey << 32) | glyphId;
    }

    /**
     * Given a font and a glyph ID within that font, locate the glyph's pre-rendered image
     * in the glyph atlas and return its cache entry. The entry stores the texture with the
     * pre-rendered glyph image, as well as the position and size of that image within the texture.
     *
     * @param font     the font (with style) to which this glyph ID belongs and which
     *                 was used to pre-render the glyph
     * @param fontSize the font size in device space
     * @param glyphId  the font specific glyph ID (should be laid-out) to lookup in the atlas
     * @return the cached glyph sprite or null if the glyph has nothing to render
     */
    @Nullable
    @RenderThread
    public GLBakedGlyph lookupGlyph(@NonNull Font font, int fontSize, int glyphId) {
        if (font instanceof OutlineFont) {
            java.awt.Font awtFont = ((OutlineFont) font).chooseFont(fontSize);
            long key = computeGlyphKey(awtFont, glyphId);
            if (mFontAtlas == null) {
                // we use mipmapping and SDF, so 2px width border around it
                ImmediateContext context = Core.requireImmediateContext();
                mFontAtlas = new GLFontAtlas(context, Engine.MASK_FORMAT_A8, GLYPH_BORDER, true);
                mDevice = (GLDevice) context.getDevice();
            }
            GLBakedGlyph glyph = mFontAtlas.getGlyph(key);
            if (glyph != null && glyph.x == Short.MIN_VALUE) {
                return cacheGlyph(
                        awtFont,
                        glyphId,
                        mFontAtlas,
                        glyph,
                        key
                );
            }
            return glyph;
        } else if (font instanceof EmojiFont emojiFont) {
            long key = computeEmojiKey(emojiFont, glyphId);
            if (mEmojiAtlas == null) {
                // we assume emoji images have a border, and no additional border
                ImmediateContext context = Core.requireImmediateContext();
                mEmojiAtlas = new GLFontAtlas(context, Engine.MASK_FORMAT_ARGB, 0, true);
                mDevice = (GLDevice) context.getDevice();
            }
            GLBakedGlyph glyph = mEmojiAtlas.getGlyph(key);
            if (glyph != null && glyph.x == Short.MIN_VALUE) {
                return cacheEmoji(
                        emojiFont,
                        glyphId,
                        mEmojiAtlas,
                        glyph,
                        key
                );
            }
            return glyph;
        } else if (font instanceof BitmapFont bitmapFont) {
            if (bitmapFont.nothingToDraw()) {
                return null;
            }
            if (bitmapFont.fitsInAtlas()) {
                long key = computeBitmapGlyphKey(bitmapFont, glyphId);
                if (mBitmapAtlas == null) {
                    ImmediateContext context = Core.requireImmediateContext();
                    mBitmapAtlas = new GLFontAtlas(context, Engine.MASK_FORMAT_ARGB, 0, false);
                    mDevice = (GLDevice) context.getDevice();
                }
                GLBakedGlyph glyph = mBitmapAtlas.getGlyph(key);
                if (glyph != null && glyph.x == Short.MIN_VALUE) {
                    return cacheBitmapGlyph(
                            bitmapFont,
                            glyphId,
                            mBitmapAtlas,
                            glyph,
                            key
                    );
                }
                return glyph;
            } else {
                // auto bake
                return bitmapFont.getBakedGlyph(glyphId);
            }
        }
        return null;
    }

    @RenderThread
    public int getCurrentTexture(int maskFormat) {
        if (maskFormat == Engine.MASK_FORMAT_A8) {
            GLTexture texture;
            if (mFontAtlas != null && (texture = mFontAtlas.mTexture) != null) {
                mDevice.generateMipmaps(texture);
                return texture.getHandle();
            }
        } else if (maskFormat == Engine.MASK_FORMAT_ARGB) {
            GLTexture texture;
            if (mEmojiAtlas != null && (texture = mEmojiAtlas.mTexture) != null) {
                mDevice.generateMipmaps(texture);
                return texture.getHandle();
            }
        }
        return 0;
    }

    public int getFontTexture() {
        return getCurrentTexture(Engine.MASK_FORMAT_A8);
    }

    public int getEmojiTexture() {
        return getCurrentTexture(Engine.MASK_FORMAT_ARGB);
    }

    @RenderThread
    public int getCurrentTexture(BitmapFont font) {
        if (font.nothingToDraw()) {
            return 0;
        }
        if (font.fitsInAtlas()) {
            GLTexture texture;
            if (mBitmapAtlas != null && (texture = mBitmapAtlas.mTexture) != null) {
                return texture.getHandle();
            }
        } else {
            return font.getCurrentTexture();
        }
        return 0;
    }

    /**
     * Compact atlases immediately.
     */
    @RenderThread
    public void compact() {
        if (mFontAtlas != null && mFontAtlas.compact()) {
            var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_A8, false);
            for (var callback : mAtlasInvalidationCallbacks) {
                callback.accept(info);
            }
        }
        if (mEmojiAtlas != null && mEmojiAtlas.compact()) {
            var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_ARGB, false);
            for (var callback : mAtlasInvalidationCallbacks) {
                callback.accept(info);
            }
        }
        if (mBitmapAtlas != null && mBitmapAtlas.compact()) {
            var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_ARGB, false);
            for (var callback : mAtlasInvalidationCallbacks) {
                callback.accept(info);
            }
        }
    }

    public void debug() {
        debug(mFontAtlas, "FontAtlas");
        debug(mEmojiAtlas, "EmojiAtlas");
        debug(mBitmapAtlas, "BitmapAtlas");
    }

    private static void debug(GLFontAtlas atlas, String name) {
        if (atlas != null) {
            String path = Bitmap.saveDialogGet(Bitmap.SaveFormat.PNG, null, name);
            atlas.debug(name, path);
        }
    }

    public void dumpInfo(PrintWriter pw) {
        if (mFontAtlas != null) {
            mFontAtlas.dumpInfo(pw, "FontAtlas");
        }
        if (mEmojiAtlas != null) {
            mEmojiAtlas.dumpInfo(pw, "EmojiAtlas");
        }
        if (mBitmapAtlas != null) {
            mBitmapAtlas.dumpInfo(pw, "BitmapAtlas");
        }
    }

    @Nullable
    @RenderThread
    private GLBakedGlyph cacheGlyph(@NonNull java.awt.Font font, int glyphCode,
                                    @NonNull GLFontAtlas atlas, @NonNull GLBakedGlyph glyph,
                                    long key) {
        // there's no need to layout glyph vector, we only draw the specific glyphCode
        // which is already laid-out in LayoutEngine
        GlyphVector vector = font.createGlyphVector(mGraphics.getFontRenderContext(), new int[]{glyphCode});

        Rectangle bounds = vector.getPixelBounds(null, 0, 0);

        if (bounds.width == 0 || bounds.height == 0) {
            atlas.setNoPixels(key);
            return null;
        }

        //glyph.advance = vector.getGlyphMetrics(0).getAdvanceX();
        glyph.x = (short) bounds.x;
        glyph.y = (short) bounds.y;
        glyph.width = (short) bounds.width;
        glyph.height = (short) bounds.height;
        int borderedWidth = bounds.width + GLYPH_BORDER * 2;
        int borderedHeight = bounds.height + GLYPH_BORDER * 2;

        if (borderedWidth > mImage.getWidth() || borderedHeight > mImage.getHeight()) {
            atlas.setNoPixels(key);
            return null;
        }

        // give it an offset to draw at origin
        mGraphics.drawGlyphVector(vector, GLYPH_BORDER - bounds.x, GLYPH_BORDER - bounds.y);

        // copy raw pixel data from BufferedImage to imageData array with one integer per pixel in 0xAARRGGBB form
        mImage.getRGB(0, 0, borderedWidth, borderedHeight, mImageData, 0, borderedWidth);

        final int size = borderedWidth * borderedHeight;
        if (atlas.getMaskFormat() == Engine.MASK_FORMAT_A8) {
            for (int i = 0; i < size; i++) {
                // alpha channel for grayscale texture
                mImageBuffer.put((byte) (mImageData[i] >>> 24));
            }
        } else {
            // used only when texture swizzle is broken
            for (int i = 0; i < size; i++) {
                mImageBuffer.put((byte) 255).put((byte) 255).put((byte) 255)
                        .put((byte) (mImageData[i] >>> 24));
            }
        }
        long src = MemoryUtil.memAddress(mImageBuffer.flip());

        boolean invalidated = atlas.stitch(glyph, src);
        if (invalidated) {
            var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_A8, true);
            for (var callback : mAtlasInvalidationCallbacks) {
                callback.accept(info);
            }
        }

        mGraphics.clearRect(0, 0, mImage.getWidth(), mImage.getHeight());
        mImageBuffer.clear();
        return glyph;
    }

    @Nullable
    @RenderThread
    private GLBakedGlyph cacheEmoji(@NonNull EmojiFont font, int glyphId,
                                    @NonNull GLFontAtlas atlas, @NonNull GLBakedGlyph glyph,
                                    long key) {
        if (glyphId == 0) {
            atlas.setNoPixels(key);
            return null;
        }
        String path = "emoji/" + font.getFileName(glyphId);
        var opts = new BitmapFactory.Options();
        opts.inPreferredFormat = Bitmap.Format.RGBA_8888;
        try (InputStream inputStream = ModernUI.getInstance().getResourceStream(ModernUI.ID, path);
             Bitmap bitmap = BitmapFactory.decodeStream(inputStream, opts)) {
            if (bitmap.getWidth() == EMOJI_SIZE && bitmap.getHeight() == EMOJI_SIZE) {
                long src = bitmap.getAddress();
                glyph.x = 0;
                glyph.y = -EMOJI_ASCENT;
                glyph.width = EMOJI_SIZE;
                glyph.height = EMOJI_SIZE;
                boolean invalidated = atlas.stitch(glyph, src);
                if (invalidated) {
                    var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_ARGB, true);
                    for (var callback : mAtlasInvalidationCallbacks) {
                        callback.accept(info);
                    }
                }
                return glyph;
            } else {
                atlas.setNoPixels(key);
                LOGGER.warn(MARKER, "Emoji is not {}x{}: {} {}", EMOJI_SIZE, EMOJI_SIZE,
                        font.getFamilyName(), path);
                return null;
            }
        } catch (Exception e) {
            atlas.setNoPixels(key);
            LOGGER.warn(MARKER, "Failed to load emoji: {} {}", font.getFamilyName(), path, e);
            return null;
        }
    }

    @Nullable
    @RenderThread
    private GLBakedGlyph cacheBitmapGlyph(@NonNull BitmapFont font, int glyphId,
                                          @NonNull GLFontAtlas atlas, @NonNull GLBakedGlyph glyph,
                                          long key) {
        long src = MemoryUtil.memAddress(mImageBuffer);
        if (!font.getGlyphImage(glyphId, src)) {
            atlas.setNoPixels(key);
            return null;
        }
        // here width and height are in pixels
        glyph.width = (short) font.getSpriteWidth();
        glyph.height = (short) font.getSpriteHeight();
        boolean invalidated = atlas.stitch(glyph, src);
        // here width and height are scaled
        font.setGlyphMetrics(glyph);
        if (invalidated) {
            var info = new AtlasInvalidationInfo(Engine.MASK_FORMAT_ARGB, true);
            for (var callback : mAtlasInvalidationCallbacks) {
                callback.accept(info);
            }
        }
        return glyph;
    }

    private void allocateImage() {
        mImage = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        mGraphics = mImage.createGraphics();

        mImageData = new int[IMAGE_SIZE * IMAGE_SIZE];
        mImageBuffer = BufferUtils.createByteBuffer(mImageData.length * 4); // auto GC

        // set background color for use with clearRect()
        mGraphics.setBackground(BG_COLOR);

        // drawImage() to this buffer will copy all source pixels instead of alpha blending them into the current image
        mGraphics.setComposite(AlphaComposite.Src);

        // this only for shape rendering, so we turn it off
        mGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        if (sAntiAliasing) {
            mGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        } else {
            mGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        }
        if (sFractionalMetrics) {
            mGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        } else {
            mGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        }
    }

    /**
     * Lookup fast char glyph with given font.
     * The pair right is the offsetX to standard '0' advance alignment (already scaled by GUI factor).
     * Because we assume FAST digit glyphs are monospaced, no matter whether it's a monospaced font.
     *
     * @param font     derived font including style
     * @param resLevel resolution level
     * @return array of all fast char glyphs, and others, or null if not supported
     */
    @Nullable
    public FastCharSet lookupFastChars(@Nonnull Font font, int resLevel) {
        if (font instanceof EmojiFont || font instanceof SpaceFont) {
            // Emojis are supported for obfuscated rendering
            return null;
        }
        if (font instanceof BitmapFont) {
            resLevel = 1;
        }
        return mFastCharMap.computeIfAbsent(
                new FontStrikeDesc(font, resLevel),
                mCacheFastChars
        );
    }

    @Nullable
    private FastCharSet cacheFastChars(@Nonnull FontStrikeDesc desc) {
        java.awt.Font awtFont = null;
        BitmapFont bitmapFont = null;
        int deviceFontSize = 1;
        if (desc.font instanceof OutlineFont) {
            deviceFontSize = TextLayoutProcessor.computeFontSize(desc.resLevel);
            awtFont = ((OutlineFont) desc.font).chooseFont(deviceFontSize);
        } else if (desc.font instanceof BitmapFont) {
            bitmapFont = (BitmapFont) desc.font;
        } else {
            return null;
        }

        // initial table
        GLBakedGlyph[] glyphs = new GLBakedGlyph[189]; // 126 - 33 + 1 + 255 - 161 + 1
        // normalized offsets
        float[] offsets = new float[glyphs.length];

        char[] chars = new char[1];
        int n = 0;

        // 48 to 57, always cache all digits for fast digit replacement
        for (int i = 0; i < 10; i++) {
            chars[0] = (char) ('0' + i);
            float advance;
            GLBakedGlyph glyph;
            // no text shaping
            if (awtFont != null) {
                GlyphVector vector = createGlyphVector(awtFont, chars);
                if (vector.getNumGlyphs() == 0) {
                    if (i == 0) {
                        LOGGER.warn(MARKER, awtFont + " does not support ASCII digits");
                        return null;
                    }
                    continue;
                }
                advance = (float) vector.getGlyphPosition(1).getX() / desc.resLevel;
                glyph = lookupGlyph(desc.font, deviceFontSize, vector.getGlyphCode(0));
                if (glyph == null) {
                    if (i == 0) {
                        LOGGER.warn(MARKER, awtFont + " does not support ASCII digits");
                        return null;
                    }
                    continue;
                }
            } else {
                var gl = bitmapFont.getGlyph(chars[0]);
                if (gl == null) {
                    if (i == 0) {
                        LOGGER.warn(MARKER, bitmapFont + " does not support ASCII digits");
                        return null;
                    }
                    continue;
                }
                advance = gl.advance;
                glyph = lookupGlyph(bitmapFont, deviceFontSize, chars[0]);
                if (glyph == null) {
                    if (i == 0) {
                        LOGGER.warn(MARKER, bitmapFont + " does not support ASCII digits");
                        return null;
                    }
                    continue;
                }
            }
            glyphs[i] = glyph;
            // '0' is standard, because it's wider than other digits in general
            if (i == 0) {
                // 0 is standard advance
                offsets[n] = advance;
            } else {
                // relative offset to standard advance, to center the glyph
                offsets[n] = (offsets[0] - advance) / 2f;
            }
            n++;
        }

        char[][] ranges = {{33, 48}, {58, 127}, {161, 256}};

        // cache only narrow chars
        for (char[] range : ranges) {
            for (char c = range[0], e = range[1]; c < e; c++) {
                chars[0] = c;
                float advance;
                GLBakedGlyph glyph;
                // no text shaping
                if (awtFont != null) {
                    GlyphVector vector = createGlyphVector(awtFont, chars);
                    if (vector.getNumGlyphs() == 0) {
                        continue;
                    }
                    advance = (float) vector.getGlyphPosition(1).getX() / desc.resLevel;
                    // too wide
                    if (advance - 1f > offsets[0]) {
                        continue;
                    }
                    glyph = lookupGlyph(desc.font, deviceFontSize, vector.getGlyphCode(0));
                } else {
                    var gl = bitmapFont.getGlyph(chars[0]);
                    // allow empty
                    if (gl == null) {
                        continue;
                    }
                    advance = gl.advance;
                    // too wide
                    if (advance - 1f > offsets[0]) {
                        continue;
                    }
                    glyph = lookupGlyph(bitmapFont, deviceFontSize, chars[0]);
                }
                // allow empty
                if (glyph != null) {
                    glyphs[n] = glyph;
                    offsets[n] = (offsets[0] - advance) / 2f;
                    n++;
                }
            }
        }

        if (n < glyphs.length) {
            glyphs = Arrays.copyOf(glyphs, n);
            offsets = Arrays.copyOf(offsets, n);
        }
        return new FastCharSet(glyphs, offsets);
    }

    /**
     * FastCharSet have uniform advances. Offset[0] is the advance for all glyphs.
     * Other offsets is the relative X offset to center the glyph. Normalized to
     * Minecraft GUI system.
     * <p>
     * This is used to render fast digits and obfuscated chars.
     */
    public static class FastCharSet extends GLBakedGlyph {

        public final GLBakedGlyph[] glyphs;
        public final float[] offsets;

        public FastCharSet(GLBakedGlyph[] glyphs, float[] offsets) {
            this.glyphs = glyphs;
            this.offsets = offsets;
        }
    }

    /**
     * Called when the atlas resized or fully reset, which means
     * texture ID changed or previous {@link GLBakedGlyph}s become invalid.
     */
    public void addAtlasInvalidationCallback(Consumer<AtlasInvalidationInfo> callback) {
        mAtlasInvalidationCallbacks.add(Objects.requireNonNull(callback));
    }

    public void removeAtlasInvalidationCallback(Consumer<AtlasInvalidationInfo> callback) {
        mAtlasInvalidationCallbacks.remove(Objects.requireNonNull(callback));
    }

    /*@SuppressWarnings("MagicConstant")
    public void measure(@NonNull char[] text, int contextStart, int contextEnd, @NonNull FontPaint paint, boolean isRtl,
                        @NonNull BiConsumer<GraphemeMetrics, FontPaint> consumer) {
        final List<FontRun> runs = paint.mTypeface.itemize(text, contextStart, contextEnd);
        float advance = 0;
        final FontMetricsInt fm = new FontMetricsInt();
        for (FontRun run : runs) {
            final Font font = run.getFont().deriveFont(paint.mFontStyle, paint.mFontSize);
            final GlyphVector vector = layoutGlyphVector(font, text, run.getStart(), run.getEnd(), isRtl);
            final int num = vector.getNumGlyphs();
            advance += vector.getGlyphPosition(num).getX();
            fm.extendBy(mGraphics.getFontMetrics(font));
        }
        consumer.accept(new GraphemeMetrics(advance, fm), paint);
    }*/
}
