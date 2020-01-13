public class Test {
  static String testString = "abcd";

  public Test() {
  }

  public void empty() {
  }

  public void testPFlow() {
    String x = "Something";
    System.out.println(x); // System.out.println <---const-data-dep---- const-str("Something")
  }
}
