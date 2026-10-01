<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>404 - Trang không tồn tại</title>
    <style>
        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background-color: #f8f9fa; }
        .box { background: #fff; padding: 40px; border-radius: 8px; display: inline-block; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
        h1 { font-size: 72px; color: #dc3545; margin: 0; }
        h2 { color: #333; }
        p { color: #6c757d; margin-bottom: 25px; }
        .btn { padding: 10px 20px; background-color: #0d6efd; color: white; text-decoration: none; border-radius: 5px; font-weight: bold; }
        .btn:hover { background-color: #0b5ed7; }
    </style>
</head>
<body>
    <div class="box">
        <h1>404</h1>
        <h2>Trang không tồn tại</h2>
        <p>Địa chỉ bạn truy cập không đúng hoặc trang này đã bị xóa.</p>
        <a href="${pageContext.request.contextPath}/" class="btn">Quay lại trang chủ</a>
    </div>
</body>
</html>