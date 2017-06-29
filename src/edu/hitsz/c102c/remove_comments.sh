perl -0pe 's|//.*?\n|\n|g; s#/\*(.|\n)*?\*/##g; s/\n\n+/\n\n/g' $1 > test.java
cp test.java $1
