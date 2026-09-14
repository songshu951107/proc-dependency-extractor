var c = net.sf.jsqlparser.expression.MySQLGroupConcat.class;
for (var m : c.getMethods()) { if (m.getName().matches("get.*")) System.out.println(m.getName() + " : " + m.getReturnType().getSimpleName()); }
/exit