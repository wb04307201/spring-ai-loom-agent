1. 使用 @httpGet 从 https://jsonplaceholder.typicode.com/todos/1 获取数据，headers为空
2. 使用 @httpPost 向 https://jsonplaceholder.typicode.com/todos 发送数据，headers为空，body为 {"title": "foo", "body": "bar", "userId": 1}
3. 使用 @httpPut 向 https://jsonplaceholder.typicode.com/todos/1 发送数据，headers为空，body为 {"title": "foo", "body": "bar", "userId": 1}
4. 使用 @httpDelete 删除 https://jsonplaceholder.typicode.com/todos/1 的数据，headers为空