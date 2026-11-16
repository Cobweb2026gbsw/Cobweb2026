# 제출 파일: def sol(matrix, target) ... end
values = STDIN.read.split.map(&:to_i)
raise 'Invalid FUNCTION input' unless values.length == 227
matrix = 15.times.map { |row| values.slice(row * 15, 15) }
target = values.slice(225, 2)
load '/source/main.rb'
result = sol(matrix, target)
raise 'sol must return boolean' unless result == true || result == false
puts result
