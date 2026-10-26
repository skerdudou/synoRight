import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Génère l'icône de synoRight (photo + cadenas) : synoRight.ico multi-tailles pour jpackage et le setup,
 * PNG des fenêtres Swing et logos du package du menu Windows 11. Chaque taille est dessinée, pas réduite.
 *
 * Lancement depuis la racine du dépôt : java native/icon/IconGenerator.java
 */
public final class IconGenerator {

	private static final Color BLUE = new Color(0x1E78D7);
	private static final Color GOLD = new Color(0xF2A900);
	private static final int[] ICO_SIZES = { 16, 20, 24, 32, 40, 48, 64, 256 };
	private static final int[] WINDOW_SIZES = { 16, 32, 48, 64, 128, 256 };

	private IconGenerator() {
	}

	public static void main(final String[] args) throws IOException {
		final Path root = Path.of(args.length > 0 ? args[0] : ".");
		writeIco(root.resolve("src/main/jpackage/synoRight.ico"));
		final Path windows = root.resolve("src/main/resources/icons");
		Files.createDirectories(windows);
		for (final int size : WINDOW_SIZES) {
			ImageIO.write(render(size), "png", windows.resolve("synoRight-" + size + ".png").toFile());
		}
		final Path assets = root.resolve("native/package/Assets");
		ImageIO.write(render(150), "png", assets.resolve("Square150x150Logo.png").toFile());
		ImageIO.write(render(44), "png", assets.resolve("Square44x44Logo.png").toFile());
		ImageIO.write(render(50), "png", assets.resolve("StoreLogo.png").toFile());
	}

	static BufferedImage render(final int s) {
		final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		// petites tailles (menus) : cadenas plus grand pour rester lisible
		final double badge = s <= 24 ? 0.60 : s <= 48 ? 0.54 : 0.50;
		photo(g, s);
		lock(g, s, badge);
		g.dispose();
		return img;
	}

	/** Carré arrondi bleu et photo blanche (montagnes, soleil). */
	private static void photo(final Graphics2D g, final int s) {
		g.setColor(BLUE);
		g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.22, s * 0.22));
		final double m = s * 0.16;
		final double pw = s - 2 * m;
		final double ph = s * 0.56;
		final double py = s * 0.18;
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Double(m, py, pw, ph, s * 0.08, s * 0.08));
		final Path2D mountains = new Path2D.Double();
		mountains.moveTo(m + pw * 0.06, py + ph * 0.90);
		mountains.lineTo(m + pw * 0.38, py + ph * 0.42);
		mountains.lineTo(m + pw * 0.58, py + ph * 0.70);
		mountains.lineTo(m + pw * 0.72, py + ph * 0.52);
		mountains.lineTo(m + pw * 0.94, py + ph * 0.90);
		mountains.closePath();
		g.setColor(BLUE);
		g.fill(mountains);
		final double sun = pw * 0.15;
		g.fill(new Ellipse2D.Double(m + pw * 0.66, py + ph * 0.14, sun, sun));
	}

	/** Pastille dorée avec cadenas blanc, en bas à droite. */
	private static void lock(final Graphics2D g, final int s, final double ratio) {
		final double d = s * ratio;
		final Ellipse2D e = new Ellipse2D.Double(s - d - s * 0.02, s - d - s * 0.02, d, d);
		final double ring = s * 0.04;
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Double(e.getX() - ring, e.getY() - ring, d + 2 * ring, d + 2 * ring));
		g.setColor(GOLD);
		g.fill(e);
		final double cx = e.getCenterX();
		final double cy = e.getCenterY();
		final double bw = d * 0.50;
		final double bh = d * 0.36;
		final double top = cy - bh * 0.15;
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Double(cx - bw / 2, top, bw, bh, d * 0.08, d * 0.08));
		g.setStroke(new BasicStroke((float) Math.max(1.2, d * 0.10), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		final double aw = bw * 0.62;
		g.draw(new Arc2D.Double(cx - aw / 2, top - aw * 0.62, aw, aw, 0, 180, Arc2D.OPEN));
		g.draw(new Line2D.Double(cx - aw / 2, top - aw * 0.12, cx - aw / 2, top));
		g.draw(new Line2D.Double(cx + aw / 2, top - aw * 0.12, cx + aw / 2, top));
	}

	/** ICO : images DIB 32 bits sous 256 px (compatibilité maximale), PNG pour 256 px. */
	private static void writeIco(final Path file) throws IOException {
		final List<byte[]> images = new ArrayList<>();
		for (final int size : ICO_SIZES) {
			images.add(size >= 256 ? png(render(size)) : dib(render(size)));
		}
		final int headerSize = 6 + 16 * ICO_SIZES.length;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
		header.putShort((short) 0).putShort((short) 1).putShort((short) ICO_SIZES.length);
		int offset = headerSize;
		for (int i = 0; i < ICO_SIZES.length; i++) {
			final int size = ICO_SIZES[i];
			header.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size))
					.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
					.putInt(images.get(i).length).putInt(offset);
			offset += images.get(i).length;
		}
		out.write(header.array());
		for (final byte[] image : images) {
			out.write(image);
		}
		Files.write(file, out.toByteArray());
	}

	private static byte[] png(final BufferedImage img) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** BITMAPINFOHEADER, pixels BGRA de bas en haut, masque AND vide (la transparence vient de l'alpha). */
	private static byte[] dib(final BufferedImage img) {
		final int s = img.getWidth();
		final int maskRow = ((s + 31) / 32) * 4;
		final ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 32)
				.putInt(0).putInt(s * s * 4).putInt(0).putInt(0).putInt(0).putInt(0);
		for (int y = s - 1; y >= 0; y--) {
			for (int x = 0; x < s; x++) {
				final int argb = img.getRGB(x, y);
				b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
			}
		}
		return b.array();
	}
}
