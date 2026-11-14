import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
public class Probe {
  public record P(@JsonProperty("title") String title,
                  @JsonAnySetter @JsonAnyGetter Map<String,Object> additionalProperties) {}
  public static void main(String[] a) throws Exception {
    ObjectMapper m = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    P p = m.readValue("{\"title\":\"t\",\"workId\":\"w1\",\"n\":2}", P.class);
    System.out.println(p);
    System.out.println(m.writeValueAsString(p));
    System.out.println(m.writeValueAsString(new P("x", null)));
  }
}
