public class dummyMainClass {
  public void dummyMainMethod(String[] args) {
    StringBuilder s = new StringBuilder("New");
    StringBuilder k = s;
    k.append(args[0]);
    k.append("Something");
    System.out.println(k.toString());
  }
}
