      * HOST VARIABLES DEFINITIONS FOR SQL
        EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       *SQL実行時の状態定数格納用
        01 SQLSTATE     PIC X(5).
        01 SQLMSG       PIC X(255).
       *郵便番号
        01 ZIPCODE      PIC X(7).
       *住所
        01 ADDRESS_NAME PIC N(50).
        EXEC SQL END DECLARE SECTION END-EXEC.
