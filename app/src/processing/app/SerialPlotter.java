/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app;

import cc.arduino.packages.BoardPort;
import processing.app.helpers.CircularBuffer;
import processing.app.helpers.Ticks;
import processing.app.legacy.PApplet;

import java.util.ArrayList;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import static processing.app.I18n.tr;

public class SerialPlotter extends AbstractMonitor {

  private final StringBuffer messageBuffer;
  private JComboBox<String> serialRates;
  private Serial serial;
  private int serialRate, xCount;

  private String teensyname=null;
  private String openport=null;
  Process program=null;
  inputPlotterPipeListener listener=null;
  errorPlotterPipeListener errors=null;
  Thread shutdown=null;

  private ArrayList<Graph> graphs;
  private final static int BUFFER_CAPACITY = 500;

  private static class Graph {
    public CircularBuffer buffer;
    private Color color;
    public String label;

    public Graph(int id) {
      buffer = new CircularBuffer(BUFFER_CAPACITY);
      color = Theme.getColorCycleColor("plotting.graphcolor", id);
    }

    public void paint(Graphics2D g, float xstep, double minY, 
                      double maxY, double rangeY, double height) {
      g.setColor(color);
      g.setStroke(new BasicStroke(1.0f));

      for (int i = 0; i < buffer.size() - 1; ++i) {
        g.drawLine(
          (int) (i * xstep), (int) transformY(buffer.get(i), minY, rangeY, height),
          (int) ((i + 1) * xstep), (int) transformY(buffer.get(i + 1), minY, rangeY, height)
        );
      }
    }

    private float transformY(double rawY, double minY, double rangeY, double height) {
      return (float) (5 + (height - 10) * (1.0 - (rawY - minY) / rangeY));
    }
  }

  private class GraphPanel extends JPanel {
    private double minY, maxY, rangeY;
    private Rectangle bounds;
    private int xOffset, xPadding;
    private final Font font;
    private final Color bgColor, gridColor, boundsColor;

    public GraphPanel() {
      font = Theme.getFont("console.font");
      bgColor = Theme.getColor("plotting.bgcolor");
      gridColor = Theme.getColor("plotting.gridcolor");
      boundsColor = Theme.getColor("plotting.boundscolor");
      xOffset = 20;
      xPadding = 20;
    }

    private Ticks computeBounds() {
      minY = Double.POSITIVE_INFINITY;
      maxY = Double.NEGATIVE_INFINITY;
      for(Graph g : graphs) {
        if (!g.buffer.isEmpty()) {
          minY = Math.min(g.buffer.min(), minY);
          maxY = Math.max(g.buffer.max(), maxY);
        }
      }

      final double MIN_DELTA = 10.0;
      if (maxY - minY < MIN_DELTA) {
        double mid = (maxY + minY) / 2;
        maxY = mid + MIN_DELTA / 2;
        minY = mid - MIN_DELTA / 2;
      }

      Ticks ticks = new Ticks(minY, maxY, 5);
      minY = Math.min(minY, ticks.getTick(0));
      maxY = Math.max(maxY, ticks.getTick(ticks.getTickCount() - 1));
      rangeY = maxY - minY;
      minY -= 0.05 * rangeY;
      maxY += 0.05 * rangeY;
      rangeY = maxY - minY;
      return ticks;
    }

    @Override
    public void paintComponent(Graphics g1) {
      Graphics2D g = (Graphics2D) g1;
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setFont(font);
      super.paintComponent(g);

      bounds = g.getClipBounds();
      setBackground(bgColor);
      if (graphs.isEmpty()) {
        return;
      }

      Ticks ticks = computeBounds();

      g.setStroke(new BasicStroke(1.0f));
      FontMetrics fm = g.getFontMetrics();
      for (int i = 0; i < ticks.getTickCount(); ++i) {
        double tick = ticks.getTick(i);
        Rectangle2D fRect = fm.getStringBounds(String.valueOf(tick), g);
        xOffset = Math.max(xOffset, (int) fRect.getWidth() + 15);

        g.setColor(boundsColor);
        // draw tick
        g.drawLine(xOffset - 5, (int) transformY(tick), xOffset + 2, (int) transformY(tick));
        // draw tick label
        g.drawString(String.valueOf(tick), xOffset - (int) fRect.getWidth() - 10, transformY(tick) - (float) fRect.getHeight() * 0.5f + fm.getAscent());
        // draw horizontal grid lines
        g.setColor(gridColor);
        g.drawLine(xOffset + 3, (int) transformY(tick), bounds.width - xPadding, (int) transformY(tick));
      }

      // handle data count
      int cnt = xCount - BUFFER_CAPACITY;
      if (xCount < BUFFER_CAPACITY) cnt = 0;
        
      double zeroTick = ticks.getTick(0);
      double lastTick = ticks.getTick(ticks.getTickCount() - 1);
      double xTickRange = BUFFER_CAPACITY / ticks.getTickCount();
        
      for (int i = 0; i < ticks.getTickCount() + 1; i++) {
          String s;
          int xValue;
          int sWidth;
          Rectangle2D fBounds;
          if  (i == 0) {
              s = String.valueOf(cnt);
              fBounds = fm.getStringBounds(s, g);
              sWidth = (int)fBounds.getWidth()/2;
              xValue = xOffset;
          } else {
              s = String.valueOf((int)(xTickRange * i)+cnt);
              fBounds = fm.getStringBounds(s, g);
              sWidth = (int)fBounds.getWidth()/2;
              xValue = (int)((bounds.width - xOffset - xPadding) * ((xTickRange * i) / BUFFER_CAPACITY) + xOffset);
          }
          // draw graph x axis, ticks and labels
          g.setColor(boundsColor);
          g.drawString(s, xValue - sWidth, (int) bounds.y + (int) transformY(zeroTick) + 15);
          g.drawLine(xValue, (int)transformY(zeroTick) - 2, xValue, bounds.y + (int)transformY(zeroTick) + 5);
          // draw vertical grid lines
          g.setColor(gridColor);
          g.drawLine(xValue, (int)transformY(zeroTick) - 3, xValue, bounds.y + (int)transformY(lastTick));
      }
      g.setColor(boundsColor);
      // draw major y axis
      g.drawLine(bounds.x + xOffset, (int) transformY(lastTick) - 5, bounds.x + xOffset, bounds.y + (int) transformY(zeroTick) + 5);
      // draw major x axis
      g.drawLine(xOffset, (int) transformY(zeroTick), bounds.width - xPadding, (int)transformY(zeroTick));
        
      g.setTransform(AffineTransform.getTranslateInstance(xOffset, 0));
      float xstep = (float) (bounds.width - xOffset - xPadding) / (float) BUFFER_CAPACITY;

      // draw legend
      int legendXOffset = 0;
      for(int i = 0; i < graphs.size(); ++i) {
        graphs.get(i).paint(g, xstep, minY, maxY, rangeY, bounds.height);
        if(graphs.size() > 1) {
          //draw legend rectangle
          g.fillRect(10 + legendXOffset, 10, 10, 10);
          legendXOffset += 13;
          //draw label
          g.setColor(boundsColor);
          String s = graphs.get(i).label;
          if(s != null && s.length() > 0) {
            Rectangle2D fBounds = fm.getStringBounds(s, g);
            int sWidth = (int)fBounds.getWidth();
            g.drawString(s, 10 + legendXOffset, 10 + (int)fBounds.getHeight() /2);
            legendXOffset += sWidth + 3;
          }
        }
      }
    }

    private float transformY(double rawY) {
      return (float) (5 + (bounds.height - 10) * (1.0 - (rawY - minY) / rangeY));
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(200, 100);
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(500, 250);
    }
  }

  public SerialPlotter(BoardPort port) {
    super(port);

    String protocol = port.getProtocol();
    if (protocol != null && protocol.equalsIgnoreCase("Teensy")) {
      String[] pieces = port.getLabel().trim().split("[\\(\\)]");
      if (pieces.length > 2 && pieces[1].startsWith("Teensy")) {
        teensyname = pieces[1];
      } else {
        teensyname = "Teensy";
      }
      disconnect();
      serialRates.hide();
      messageBuffer = new StringBuffer();
      graphs = new ArrayList<>();
      return; // end of Teensy's ctor
    }

    serialRate = PreferencesData.getInteger("serial.debug_rate");
    serialRates.setSelectedItem(serialRate + " " + tr("baud"));
    onSerialRateChange(event -> {
      String wholeString = (String) serialRates.getSelectedItem();
      String rateString = wholeString.substring(0, wholeString.indexOf(' '));
      serialRate = Integer.parseInt(rateString);
      PreferencesData.set("serial.debug_rate", rateString);
      try {
        close();
        Thread.sleep(100); // Wait for serial port to properly close
        open();
      } catch (Exception e) {
        // ignore
      }
    });

    messageBuffer = new StringBuffer();
    graphs = new ArrayList<>();
  }

  protected void onCreateWindow(Container mainPane) {
    mainPane.setLayout(new BorderLayout());

    GraphPanel graphPanel = new GraphPanel();

    mainPane.add(graphPanel, BorderLayout.CENTER);

    JPanel pane = new JPanel();
    pane.setLayout(new BoxLayout(pane, BoxLayout.X_AXIS));
    pane.setBorder(new EmptyBorder(4, 4, 4, 4));

    serialRates = new JComboBox<>();
    for (String serialRateString : serialRateStrings) serialRates.addItem(serialRateString + " " + tr("baud"));

    serialRates.setMaximumSize(serialRates.getMinimumSize());

    pane.add(Box.createHorizontalGlue());
    pane.add(Box.createRigidArea(new Dimension(8, 0)));
    pane.add(serialRates);

    mainPane.add(pane, BorderLayout.SOUTH);
  }

  protected void onEnableWindow(boolean enable) {
    serialRates.setEnabled(enable);
  }

  private void onSerialRateChange(ActionListener listener) {
    serialRates.addActionListener(listener);
  }

  public void message(final String s) {
    messageBuffer.append(s);
    while (true) {
      int linebreak = messageBuffer.indexOf("\n");
      if (linebreak == -1) {
        break;
      }
      xCount++;
      String line = messageBuffer.substring(0, linebreak);
      messageBuffer.delete(0, linebreak + 1);

      line = line.trim();
      String[] parts = line.split("[, \t]+");
      if(parts.length == 0) {
        continue;
      }

      int validParts = 0;
      int validLabels = 0;
      for(int i = 0; i < parts.length; ++i) {
        Double value = null;
        String label = null;
        
        // column formated name value pair
        if(parts[i].contains(":")) {
          // get label
          String[] subString = parts[i].split("[:]+");
            
          if(subString.length > 0) {
            int labelLength = subString[0].length();
              
            if(labelLength > 32) {
                labelLength = 32;
            }
            label = subString[0].substring(0, labelLength);
          } else {
            label = "";
          }
            
          if(subString.length > 1) {
            parts[i] = subString[1];
          } else {
            parts[i] = "";
          }
        }

        try {
          value = Double.valueOf(parts[i]);
        } catch (NumberFormatException e) {
          // ignored
        }
        //CSV header
        if(label == null && value == null) {
          label = parts[i];
        }
        
        if(value != null) {
          if(validParts >= graphs.size()) {
            graphs.add(new Graph(validParts));
          }
          graphs.get(validParts).buffer.add(value);
          validParts++;
        }
        if(label != null) {
          if(validLabels >= graphs.size()) {
            graphs.add(new Graph(validLabels));
          }
          graphs.get(validLabels).label = label;
          validLabels++;
        }
        if(validParts > validLabels) validLabels = validParts;
        else if(validLabels > validParts) validParts = validLabels;
      }
    }

    SwingUtilities.invokeLater(SerialPlotter.this::repaint);
  }

  public void open() throws Exception {
    super.open();

    String protocol = getBoardPort().getProtocol();
    if (protocol != null && protocol.equalsIgnoreCase("Teensy")) {
      String port = getBoardPort().getAddress();
      if (openport != null && port.equals(openport) && program != null
        && listener != null && listener.isAlive() && errors != null && errors.isAlive()) {
          return; // correct port is already open
      }
      if (program != null || listener != null || errors != null) {
        close(); // another port was already open, close first
      }
      String[] cmdline;
      String command = BaseNoGui.getHardwarePath() + File.separator +
        "tools" + File.separator + "teensy_serialmon";
      cmdline = new String[2];
      cmdline[0] = command;
      cmdline[1] = port;
      try {
        program = Runtime.getRuntime().exec(cmdline);
      } catch (Exception e1) {
        System.err.println("Unable to run teensy_serialmon");
        program = null;
      }
      if (program != null) {
        openport = new String(port);
        listener = new inputPlotterPipeListener();
        listener.input = program.getInputStream();
        listener.output = this;
        listener.start();
        errors = new errorPlotterPipeListener();
        errors.input = program.getErrorStream();
        errors.output = this;
        errors.start();
        if (shutdown != null) Runtime.getRuntime().removeShutdownHook(shutdown);
        shutdown = new Thread() {
          public void run() {
            if (program != null) {
              program.destroy();
              program = null;
            }
            if (listener != null) {
              if (listener.isAlive()) listener.interrupt();
              listener = null;
            }
            if (errors != null) {
              if (errors.isAlive()) errors.interrupt();
              errors = null;
            }
          }
        };
        Runtime.getRuntime().addShutdownHook(shutdown);
      } else {
        super.close();
      }
      return; // end of Teensy's open
    }

    if (serial != null) return;

    int attempt = 1;
    while (true) {
      try {
        serial = new Serial(getBoardPort().getAddress(), serialRate) {
          @Override
          protected void message(char buff[], int n) {
            addToUpdateBuffer(buff, n);
          }
        };
        break;
      } catch (SerialException e) {
        if (++attempt > 20) throw e; // try up to 2 seconds to open port
      }
      try {
        Thread.sleep(100);
      } catch (Exception e) {
      }
    }
  }

  public void close() throws Exception {
    String protocol = getBoardPort().getProtocol();
    if (protocol != null && protocol.equalsIgnoreCase("Teensy")) {
      //System.out.println("close Teensy");
      if (program != null) {
        program.destroy();
        program = null;
      }
      if (listener != null) {
        if (listener.isAlive()) listener.interrupt();
        listener = null;
      }
      if (errors != null) {
        if (errors.isAlive()) errors.interrupt();
        errors = null;
      }
      if (shutdown != null) {
        Runtime.getRuntime().removeShutdownHook(shutdown);
        shutdown = null;
      }
      openport = null;
      setTitle("[offline] (" + teensyname + ")");
      super.close();
      return; // end of Teensy's close
    }

    if (serial != null) {
      super.close();
      int[] location = getPlacement();
      String locationStr = PApplet.join(PApplet.str(location), ",");
      PreferencesData.set("last.serial.location", locationStr);
      serial.dispose();
      serial = null;
    }
  }

  public void opened(String device, String usbtype) {
    //System.out.println("opened Teensy");
    setTitle(device + " (" + teensyname + ") " + usbtype);
    graphs = new ArrayList<>();  // clear old waveform
  }

  public void disconnect() {
    //System.out.println("disconnect Teensy");
    setTitle("[offline] (" + teensyname + ")");
    if (messageBuffer != null) messageBuffer.setLength(0);
  }
}

class inputPlotterPipeListener extends Thread {
        InputStream input;
        SerialPlotter output;

        public void run() {
                byte[] buffer = new byte[65536];
                try {
                        while (true) {
                                int num = input.read(buffer);
                                if (num <= 0) break;
                                //System.out.println("inputPlotterPipeListener, num=" + num);
                                String text = new String(buffer, 0, num);
                                //System.out.println("inputPlotterPipeListener, text=" + text);
                                char[] chars = text.toCharArray();
                                output.addToUpdateBuffer(chars, chars.length);
                                //System.out.println("inputPlotterPipeListener, out=" + chars.length);
                        }
                } catch (Exception e) { }
                //System.out.println("inputPlotterPipeListener thread exit");
        }

}

class errorPlotterPipeListener extends Thread {
        InputStream input;
        SerialPlotter output;

        public void run() {
                InputStreamReader reader = new InputStreamReader(input);
                BufferedReader in = new BufferedReader(reader);
                try {
                        while (true) {
                                String line = in.readLine();
                                //System.err.print("line: ");
                                if (line.startsWith("Opened ")) {
                                        String parts[] = line.trim().split(" ", 3);
                                        if (parts.length == 3) {
                                                output.opened(parts[1], parts[2]);
                                        }
                                } else if (line.startsWith("Disconnect ")) {
                                        output.disconnect();
                                } else {
                                        System.err.println(line);
                                }
                        }
                } catch (Exception e) { }
                //System.out.println("errorPlotterPipeListener thread exit");
        }

}

