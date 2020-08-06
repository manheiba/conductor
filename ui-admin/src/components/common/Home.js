import { Jumbotron } from 'react-bootstrap';
import React, { Component } from 'react';

class Introduction extends Component {
  constructor(props) {
    super(props);
  }

  render() {
    return (
        <Jumbotron className="jumbotron jumbotron-fluid">
        
        <div className="row">
        <p>我们追求的不仅仅是编排服务，更是编排梦想！！！</p> 
    	<img src="/images/conductor.png" class="rounded-circle" alt="Cinque Terre"></img> 
    	</div>
    	
        </Jumbotron>
        
    );
  }
}

export default Introduction;
