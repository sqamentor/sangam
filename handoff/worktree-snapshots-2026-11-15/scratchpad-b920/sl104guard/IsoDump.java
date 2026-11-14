import java.util.*;
public class IsoDump {
  public static void main(String[] a) {
    TreeSet<String> c = new TreeSet<>(); for (Currency x : Currency.getAvailableCurrencies()) c.add(x.getCurrencyCode());
    System.out.println("CURRENCIES=" + String.join(",", c));
    System.out.println("COUNTRIES=" + String.join(",", new TreeSet<>(Arrays.asList(Locale.getISOCountries()))));
  }
}
