package org.radargun.reporting.html;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.radargun.config.Cluster;
import org.radargun.config.Property;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.reporting.Timeline;

/**
 * Presents {@link Timeline timelines} from all slaves and master.
 * Uses {@link TimelineChart} to generate image files.
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class TimelineDocument extends HtmlDocument {
   private static final Log log = LogFactory.getLog(TimelineDocument.class);
   private final Configuration configuration;
   private final String configName;
   private final String title;
   private final Cluster cluster;
   private List<Timeline> timelines;
   private Map<String, Double> minValues = new HashMap<String, Double>();
   private Map<String, Double> maxValues = new HashMap<String, Double>();
   private Map<String, Integer> valueCategories = new TreeMap<String, Integer>();
   private Map<String, Integer> eventCategories = new TreeMap<String, Integer>();
   private long startTimestamp = Long.MAX_VALUE, endTimestamp = Long.MIN_VALUE;

   public TimelineDocument(Configuration configuration, String directory, String configName, String title, List<Timeline> timelines, Cluster cluster) {
      super(directory, "timeline_" + configName + ".html", title + " Timeline");
      this.title = title;
      this.configuration = configuration;
      this.timelines = new ArrayList<Timeline>(timelines);
      Collections.sort(this.timelines);
      this.configName = configName;
      this.cluster = cluster;

      for (Timeline timeline : this.timelines) {
         startTimestamp = Math.min(startTimestamp, timeline.getFirstTimestamp());
         endTimestamp = Math.max(endTimestamp, timeline.getLastTimestamp());
         for (String category : timeline.getEventCategories()) {
            if (!eventCategories.containsKey(category)) {
               eventCategories.put(category, eventCategories.size());
            }
         }

         for (String category : timeline.getValueCategories()) {
            if (!valueCategories.containsKey(category)) {
               valueCategories.put(category, valueCategories.size());
            }

            List<Timeline.Value> events = timeline.getValues(category);
            double min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (Timeline.Event event : events) {
               if (event instanceof Timeline.Value) {
                  double d = ((Timeline.Value) event).value.doubleValue();
                  max = Math.max(max, d);
                  min = Math.min(min, d);
               }
            }
            if (min <= max) {
               Double prevMin = minValues.get(category);
               Double prevMax = maxValues.get(category);
               minValues.put(category, prevMin == null ? min : Math.min(prevMin, min));
               maxValues.put(category, prevMax == null ? max : Math.max(prevMax, max));
            }
         }
      }
      // in order to show event categories, we need at least one value category
      if (valueCategories.isEmpty()) {
         String defaultCategory = "&nbsp;";
         valueCategories.put(defaultCategory, 0);
         minValues.put(defaultCategory, 0d);
         maxValues.put(defaultCategory, 0d);
      }
   }

   public void writeTimelines() throws IOException {
      write("<center><h1>" + title + " Timeline</h1></center>");

      write("<table style=\"float: left\">");
      final AtomicBoolean firstDomain = new AtomicBoolean(true);
      final String relativeDomainFile = "domain_" + configName + "_relative.png";
      final String absoluteDomainFile = "domain_" + configName + "_absolute.png";
      ArrayList<Future> chartTaskFutures = new ArrayList<>();
      for (Map.Entry<String, Integer> valueEntry : valueCategories.entrySet()) {
         final String valueCategory = valueEntry.getKey();
         final int valueCategoryId = valueEntry.getValue();
         write(String.format("<tr><th colspan=\"2\">%s</th></tr>", valueCategory));

         /* Range */
         Double min = minValues.get(valueCategory);
         if (min == null || min > 0) min = 0d;
         Double max = maxValues.get(valueCategory);
         if (max == null || max < 0) max = 0d;
         minValues.put(valueCategory, min);
         maxValues.put(valueCategory, max);

         final String rangeFile = String.format("timeline_%s_%d_range.png", configName, valueCategoryId);
         write(String.format("<tr><td style=\"text-align: right\"><img src=\"%s\"></td>\n", rangeFile));

         /* Charts */
         write(String.format("<td><div style=\"position: relative; width: %d; height: %d\">\n", configuration.width, configuration.height));
         final AtomicBoolean firstRange = new AtomicBoolean(true);
         for (Timeline timeline : timelines) {
            List<Timeline.Value> categoryValues = timeline.getValues(valueCategory);
            final List<Timeline.Value> values = categoryValues != null ? categoryValues : Collections.EMPTY_LIST;
            final int slaveIndex = timeline.slaveIndex;
            final String valueChartFile = String.format("timeline_%s_v%d_%d.png", configName, valueCategoryId, slaveIndex);
            write(String.format("<img id=\"layer_%d_%d\" src=\"%s\" style=\"position: absolute; left: 0; top: 0\">\n",
                  valueCategoryId, slaveIndex, valueChartFile));

            chartTaskFutures.add(HtmlReporter.executor.submit(new Callable<Void>() {
               @Override
               public Void call() throws Exception {
                  log.debug("Generating chart for " + valueCategory);
                  TimelineChart chart = new TimelineChart();
                  chart.setDimensions(configuration.width, configuration.height);

                  chart.setEvents(values, slaveIndex, startTimestamp, endTimestamp, minValues.get(valueCategory) * 1.1, maxValues.get(valueCategory) * 1.1);


                  chart.saveChart(directory + File.separator + valueChartFile);

                  if (firstRange.compareAndSet(true, false)) {
                     chart.saveRange(directory + File.separator + rangeFile);
                  }
                  if (firstDomain.compareAndSet(true, false)) {
                     chart.saveRelativeDomain(directory + File.separator + relativeDomainFile);
                     chart.saveAbsoluteDomain(directory + File.separator + absoluteDomainFile);
                  }
                  return null;
               }
            }));

            for (String eventCategory : timeline.getEventCategories()) {
               if (timeline.getEvents(eventCategory) == null) continue;

               int eventCategoryId = eventCategories.get(eventCategory);
               String eventChartFile = String.format("timeline_%s_e%d_%d.png", configName, eventCategoryId, timeline.slaveIndex);
               write(String.format("<img id=\"layer_%d_%d_%d\" src=\"%s\" style=\"position: absolute; left: 0; top: 0\">\n",
                     valueCategoryId, eventCategoryId, timeline.slaveIndex, eventChartFile));
            }
         }
         write("</div></td></tr>");
         write("<tr><td>&nbsp;</td><td><img src=\"" + relativeDomainFile + "\"></td></tr>");
         write("<tr><td>&nbsp;</td><td><img src=\"" + absoluteDomainFile + "\"></td></tr>");
      }
      write("</table>");

      for (Timeline timeline : timelines) {
         final int slaveIndex = timeline.slaveIndex;
         for (String ec : timeline.getEventCategories()) {
            final String eventCategory = ec;
            final List<Timeline.Event> events = timeline.getEvents(eventCategory);
            if (events == null) continue;

            chartTaskFutures.add(HtmlReporter.executor.submit(new Callable<Void>() {
               @Override
               public Void call() throws Exception {
                  TimelineChart chart = new TimelineChart();
                  chart.setDimensions(configuration.width, configuration.height);
                  chart.setEvents(events, slaveIndex, startTimestamp, endTimestamp, 0, 0);

                  String chartFile = String.format("timeline_%s_e%d_%d.png", configName, eventCategories.get(eventCategory), slaveIndex);
                  chart.saveChart(directory + File.separator + chartFile);
                  return null;
               }
            }));
         }
      }
      /* wait until all charts are generated */
      for (Future f : chartTaskFutures) {
         try {
            f.get();
         } catch (Exception e) {
            log.error("Failed to generate on of the charts: ", e);
         }
      }

      /* Checkboxes */
      write("<div style=\"float: left;\">\n");
      for (Map.Entry<String, Integer> eventEntry : eventCategories.entrySet()) {
         write(String.format("<input id=\"cat_%d\" type=\"checkbox\" checked=\"checked\" onClick=\"", eventEntry.getValue()));

         for (Timeline timeline : timelines) {
            for (int valuesId : valueCategories.values())  {
               write(String.format("reset_display('layer_%d_%d_%d', this.checked && is_checked('slave_%d'), 'block');",
                     valuesId, eventEntry.getValue(), timeline.slaveIndex, timeline.slaveIndex));
            }
         }
         write(String.format("\"><strong>%s</strong><br>\n", eventEntry.getKey()));
      }

      write("<br><br>");
      List<Cluster.Group> groups = cluster.getGroups();
      for (Timeline timeline : timelines) {
         write(String.format("<span style=\"background-color: #%06X;\">&nbsp;</span>" +
               "<input type=\"checkbox\" checked=\"checked\" id=\"slave_%d\" onClick=\"",
               TimelineChart.getColorForIndex(timeline.slaveIndex), timeline.slaveIndex));
         for (int valuesId : valueCategories.values())  {
            write(String.format("reset_display('layer_%d_%d', this.checked, 'block');",
                  valuesId, timeline.slaveIndex));
            for (int eventsId : eventCategories.values()) {
               write(String.format("reset_display('layer_%d_%d_%d', this.checked && is_checked('cat_%d'), 'block');",
                     valuesId, eventsId, timeline.slaveIndex, eventsId));
            }
         }
         if (timeline.slaveIndex >= 0) {
            write("\"><strong>Slave ");
            if (groups.size() > 1)  {
               write(String.format("%d (%s)", timeline.slaveIndex, cluster.getGroup(timeline.slaveIndex).name));
            } else {
               write(String.valueOf(timeline.slaveIndex));
            }
            write("</strong><br>\n");
         } else {
            write("\"><strong>Master</strong><br>\n");
         }
      }
      if (groups.size() > 1) {
         for (int groupId = 0; groupId < groups.size(); ++groupId) {
            write(String.format("<span>&nbsp;</span><input type=\"checkbox\" checked=\"checked\" id=\"group_%d\" onClick=\"", groupId));
            for (int slaveIndex : cluster.getSlaves(groups.get(groupId).name)) {
               for (int valuesId : valueCategories.values()) {
                  write(String.format("document.getElementById('slave_%d').checked = this.checked;", slaveIndex));
                  write(String.format("reset_display('layer_%d_%d', this.checked, 'block');",
                        valuesId, slaveIndex));
                  for (int eventsId : eventCategories.values()) {
                     write(String.format("reset_display('layer_%d_%d_%d', this.checked && is_checked('cat_%d'), 'block');",
                           valuesId, eventsId, slaveIndex, eventsId));
                  }
               }
            }
            write("\"><strong>Group " + groups.get(groupId).name + "</strong><br>\n");
         }
      }
      write("</div>");
   }

   @Override
   protected void writeScripts() {
      write("function is_checked(id, checked) {\n");
      write("    var element = document.getElementById(id);\n");
      write("    return element == null ? false : element.checked\n");
      write("}\n");
      write("function reset_display(id, checked, display) {\n");
      write("    var element = document.getElementById(id);\n");
      write("    if (element == null) return;\n");
      write("    if (checked) {\n");
      write("        element.style.display = display;\n");
      write("    } else {\n");
      write("        element.style.display = 'none';\n");
      write("    }\n}\n");
   }

   public static class Configuration {
      @Property(name = "chart.width", doc = "Width of the chart in pixels. Default is 1024.")
      private int width = 1024;

      @Property(name = "chart.height", doc = "Height of the chart in pixels. Default is 500.")
      private int height = 500;
   }
}
