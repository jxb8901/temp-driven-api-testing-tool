select order_id as ORDER_ID,
       status as STATUS,
       created_at as CREATED_AT
  from orders
 where customer_id = ?
   and created_at >= ?
 order by created_at
