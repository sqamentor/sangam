import tools.jackson.databind.json.JsonMapper;
public class JackCheck {
  public record G(String frequency, int fiscalStartYear, int dueOffsetDays) {}
  public static void main(String[] a) throws Exception {
    var m = JsonMapper.builder().build();
    try { System.out.println("parsed: " + m.readValue("{\"frequency\":\"MONTHLY\",\"dueOffsetDays\":5}", G.class)); }
    catch (Exception e) { System.out.println("refused: " + e.getClass().getSimpleName() + ": " + e.getMessage().lines().findFirst().orElse("")); }
  }
}
