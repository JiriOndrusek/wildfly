<html>
<head>
<title>JSP Test</title>

<%
String message = "Hello, World";

%>

</head>

<body>

<h1><%= message%></h1>




<h2><%= new java.util.Date() %></h2>
<h3><%= request.getSession().getId() %></h2>

<%
response.sendRedirect("hello.jsp");
request.getSession().invalidate();
%>
</body>
</html>
