package brooklyn.rest.api;

import java.net.URI;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;

import brooklyn.entity.basic.EntityLocal;
import brooklyn.event.Sensor;

import com.google.common.collect.ImmutableMap;

public class SensorSummary {

  private final String name;
  private final String type;
  @JsonSerialize(include=Inclusion.NON_NULL)
  private final String description;
  @JsonSerialize(include=Inclusion.NON_NULL)
  private final Map<String, URI> links;

  public SensorSummary(
      @JsonProperty("name") String name,
      @JsonProperty("type") String type,
      @JsonProperty("description") String description,
      @JsonProperty("links") Map<String, URI> links
  ) {
    this.name = name;
    this.type = type;
    this.description = description;
    this.links = links != null ? ImmutableMap.copyOf(links) : null;
  }

  public SensorSummary(Application application, EntityLocal entity, Sensor<?> sensor) {
    this.name = sensor.getName();
    this.type = sensor.getTypeName();
    this.description = sensor.getDescription();

    String applicationUri = "/v1/applications/" + application.getSpec().getName();
    String entityUri = applicationUri + "/entities/" + entity.getId();

    this.links = ImmutableMap.<String, URI>builder()
        .put("self", URI.create(entityUri + "/sensors/" + sensor.getName()))
        .put("application", URI.create(applicationUri))
        .put("entity", URI.create(entityUri))
        .build();
  }

  public static SensorSummary forCatalog(Sensor<?> sensor) {
      return new SensorSummary(sensor.getName(), sensor.getTypeName(),
              sensor.getDescription(), null);
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public String getDescription() {
    return description;
  }

  public Map<String, URI> getLinks() {
    return links;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    SensorSummary that = (SensorSummary) o;

    if (description != null ? !description.equals(that.description) : that.description != null)
      return false;
    if (links != null ? !links.equals(that.links) : that.links != null)
      return false;
    if (name != null ? !name.equals(that.name) : that.name != null)
      return false;
    if (type != null ? !type.equals(that.type) : that.type != null)
      return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (type != null ? type.hashCode() : 0);
    result = 31 * result + (description != null ? description.hashCode() : 0);
    result = 31 * result + (links != null ? links.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "SensorSummary{" +
        "name='" + name + '\'' +
        ", type='" + type + '\'' +
        ", description='" + description + '\'' +
        ", links=" + links +
        '}';
  }
}
