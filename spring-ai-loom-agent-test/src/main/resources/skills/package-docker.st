按照下面的步骤执行，并在每步完成后回复用户
1. 检查 sql-forge-demo 是否存在
   如果存在删除目录
2. 使用 @gitClone 克隆公开仓库 https://gitee.com/wb04307201/sql-forge-demo.git 到 sql-forge-demo
3. 在 sql-forge-demo\sql-forge-demo 目录里 执行命令
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-17\bin"
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   mvn clean package -DskipTests
   进行打包
4. 为 sql-forge-demo\sql-forge-demo\target\sql-forge-demo-0.0.1-SNAPSHOT.jar 创建dockerfile
   注意：
   使用java 17
   jar的默认端口是8081
5. 创建 docker 镜像 镜像名为sql-forge-demo 完成后回复用户 镜像创建完成
6. 启动容器‌，端口从8081映射到8081
7. 使用<a>标签展示服务访问地址，地址为http://localhost:8081/sql/forge/web，用户点击打开新的标签页访问